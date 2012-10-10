package cc.factorie

import cc.factorie._
import cc.factorie.optimize._
import cc.factorie.util._
import app.classify
import cc.factorie.la._
import classify.{ModelBasedClassifier, LogLinearModel}
import scala.collection.mutable._
import collection.parallel.mutable.ParSeq
import collection.GenSeq
import java.io.File
import io.Source

/**
 * Created by IntelliJ IDEA.
 * User: Alexandre Passos, Luke Vilnis
 * Date: 10/5/12
 * Time: 11:13 AM
 * To change this template use File | Settings | File Templates.
 */
//trait PieceState { def merge(other: PieceState): PieceState }

// Pieces are thread safe
trait Piece[C] {
  def accumulateValueAndGradient(model: Model[C], gradient: TensorAccumulator, value: DoubleAccumulator): Unit
  def accumulateGradient(model: Model[C], gradient: TensorAccumulator): Unit = accumulateValueAndGradient(model, gradient, NoopDoubleAccumulator)
  def accumulateValue(model: Model[C], value: DoubleAccumulator): Unit = accumulateValueAndGradient(model, NoopTensorAccumulator, value)
  //def state: PieceState
  //def updateState(state: PieceState)
}

trait PiecewiseLearner[C] {
  def model: Model[C]
  def process(pieces:GenSeq[Piece[C]]): Unit
}

class BatchPiecewiseLearner[C](val optimizer: GradientOptimizer, val model: Model[C]) extends PiecewiseLearner[C] {
  val gradient = model.weightsTensor.copy
  val gradientAccumulator = new LocalTensorAccumulator(gradient.asInstanceOf[WeightsTensor])
  val valueAccumulator = new LocalDoubleAccumulator(0.0)
  def process(pieces: GenSeq[Piece[C]]): Unit = {
    if (isConverged) return
    gradient.zero()
    valueAccumulator.value = 0.0
    // Note that nothing stops us from computing the gradients in parallel if the machine is 64-bit...
    pieces /*.par */ .foreach(piece => piece.accumulateValueAndGradient(model, gradientAccumulator, valueAccumulator))
    println("Gradient: " + gradient + "\nLoss: " + valueAccumulator.value)
    optimizer.step(model.weightsTensor, gradient, valueAccumulator.value, 0)
  }
  def isConverged = optimizer.isConverged
}

class SGDPiecewiseLearner[C](val optimizer: GradientOptimizer, val model: Model[C]) extends PiecewiseLearner[C] {
  val gradient = new ThreadLocal[Tensor] {override def initialValue = model.weightsTensor.asInstanceOf[WeightsTensor].copy}
  val gradientAccumulator = new ThreadLocal[LocalTensorAccumulator] {override def initialValue = new LocalTensorAccumulator(gradient.get.asInstanceOf[WeightsTensor])}
  val valueAccumulator = new ThreadLocal[LocalDoubleAccumulator] {override def initialValue = new LocalDoubleAccumulator(0.0)}

  override def process(pieces: GenSeq[Piece[C]]): Unit = {
    // Note that nothing stops us from computing the gradients in parallel if the machine is 64-bit
    pieces.foreach(piece => {
      gradient.get.zero()
//      valueAccumulator.get.value = 0.0
      piece.accumulateValueAndGradient(model, gradientAccumulator.get, valueAccumulator.get)
      optimizer.step(model.weightsTensor, gradient.get, valueAccumulator.get.value, 0)
//      println("Step!")
    })
  }

  def isConverged = false
}

class HogwildPiecewiseLearner[C](optimizer: GradientOptimizer, model: Model[C]) extends SGDPiecewiseLearner[C](optimizer, model) {
  override def process(pieces: GenSeq[Piece[C]]) = super.process(pieces.par)
}

object LossFunctions {
  type LossFunction = (Double, Double) => (Double, Double)
  val hingeLoss: LossFunction = (prediction, label) => {
    val loss = -math.max(0, 1 - prediction * label)
    (loss, math.signum(loss) * label)
  }
  def sigmoid(x: Double): Double = 1.0 / (1 + math.exp(-x))
  val logLoss: LossFunction = (prediction, label) => {
    val loss = math.log(1 + math.exp(-prediction * label))
    (loss, math.signum(label) * sigmoid(-prediction * label))
  }
  type MultiClassLossFunction = (Tensor1, Int) => (Double, Tensor1)
  val logMultiClassLoss: MultiClassLossFunction = (prediction, label) => {
    val normed = prediction.expNormalized
    val loss = math.log(normed(label))
    normed *= -1
    normed += (label, 1.0)
    val gradient = normed.asInstanceOf[Tensor1]
    (loss, gradient)
  }
  val hingeMultiClassLoss: MultiClassLossFunction = (prediction, label) => {
    val loss = -math.max(0, 1 - prediction(label))
    val predictedLabel = prediction.maxIndex
    val gradient =
      if (label == predictedLabel)
        new UniformTensor1(prediction.size, 0.0)
      else {
        val g = new DenseTensor1(prediction.size, 0.0)
        g(label) += 1.0
        g(predictedLabel) += -1.0
        g
      }
    (loss, gradient)
  }
}

class MultiClassGLMPiece(featureVector: Tensor1, label: Int, lossAndGradient: LossFunctions.MultiClassLossFunction) extends Piece[Variable] {
  //def updateState(state: PieceState): Unit = { }
  def state = null
  def accumulateValueAndGradient(model: Model[Variable], gradient: TensorAccumulator, value: DoubleAccumulator) {
    // println("featureVector size: %d weights size: %d" format (featureVector.size, model.weights.size))
    val weightsMatrix = model.weightsTensor.asInstanceOf[WeightsTensor](DummyFamily).asInstanceOf[Tensor2]
    val prediction = weightsMatrix matrixVector featureVector
//    println("Prediction: " + prediction)
    val (loss, sgrad) = lossAndGradient(prediction, label)
    value.accumulate(loss)
//    println("Stochastic gradient: " + sgrad)
    gradient.addOuter(DummyFamily, sgrad, featureVector)
  }
}

// This family exists only to  allow us to map a single tensor into a WeightsTensor
object DummyFamily extends DotFamily {
  type FamilyType = this.type

  type NeighborType1 = Variable
  type FactorType = Factor

  def weights = null
}

class GLMPiece(featureVector: Tensor, label: Double, lossAndGradient: LossFunctions.LossFunction) extends Piece[Variable] {
  def state = null
  def accumulateValueAndGradient(model: Model[Variable], gradient: TensorAccumulator, value: DoubleAccumulator) {
    val (loss, sgrad) = lossAndGradient(featureVector dot model.weightsTensor.asInstanceOf[WeightsTensor](DummyFamily), label)
    value.accumulate(loss)
    featureVector.activeDomain.foreach(x => gradient.accumulate(DummyFamily, x, sgrad))
  }
}

class BPMaxLikelihoodPiece[A <: cc.factorie.DiscreteValue](labels: Seq[LabeledMutableDiscreteVarWithTarget[A]]) extends Piece[Variable] {
  def state = null

  labels.foreach(_.setToTarget(null))

  def accumulateValueAndGradient(model: Model[Variable], gradient: TensorAccumulator, value: DoubleAccumulator) {
    val fg = BP.inferTreewiseSum(labels.toSet, model)
    // The log loss is - score + log Z
    value.accumulate(new Variable2IterableModel[Variable](model).currentScore(labels) - fg.bpFactors.head.calculateLogZ)

    fg.bpFactors.foreach(f => {
      val factor = f.factor.asInstanceOf[DotFamily#Factor]
      gradient.accumulate(factor.family, factor.currentStatistics)
      gradient.accumulate(factor.family, f.calculateMarginal * -1)
    })
  }
}

// The following trait has convenience methods for adding to an accumulator the
// factors that touch a pair of Good/Bad variables
object GoodBadPiece {
  def addGoodBad[C](gradient: TensorAccumulator, model: Model[C], good: C, bad: C) {
    model.factors(good).foreach(f => {
      f match {
        case f: DotFamily#Factor => gradient.accumulate(f.family, f.currentStatistics)
        case _ => Nil
      }
    })
    model.factors(bad).foreach(f => {
      f match {
        case f: DotFamily#Factor => gradient.accumulate(f.family, f.currentStatistics * -1.0)
        case _ => Nil
      }
    })
  }
}

// The following piece implements the domination loss function: it penalizes models that rank any of
// the badCandates above any of the goodCandidates.
// The actual loss used in this version is the maximum (margin-augmented) difference between
// goodCandidates and badCandidates.
// See DominationLossPieceAllGood for one that outputs a gradient for all goodCandidates
class DominationLossPiece(goodCandidates: Seq[Variable], badCandidates: Seq[Variable]) extends Piece[Variable] {
  def accumulateValueAndGradient(model: Model[Variable], gradient: TensorAccumulator, value: DoubleAccumulator) {
    val goodScores = goodCandidates.map(model.currentScore(_))
    val badScores = badCandidates.map(model.currentScore(_))
    val worstGoodIndex = goodScores.zipWithIndex.maxBy(i => -i._1)._2
    val bestBadIndex = badScores.zipWithIndex.maxBy(i => i._1)._2
    if (goodScores(worstGoodIndex) < badScores(bestBadIndex) + 1) {
      value.accumulate(goodScores(worstGoodIndex) - badScores(bestBadIndex) - 1)
      GoodBadPiece.addGoodBad(gradient, model, goodCandidates(worstGoodIndex), badCandidates(bestBadIndex))
    }
  }
}

class DominationLossPieceAllGood(goodCandidates: Seq[Variable], badCandidates: Seq[Variable]) extends Piece[Variable] {
  def accumulateValueAndGradient(model: Model[Variable], gradient: TensorAccumulator, value: DoubleAccumulator) {
    val goodScores = goodCandidates.map(model.currentScore(_))
    val badScores = badCandidates.map(model.currentScore(_))
    val bestBadIndex = badScores.zipWithIndex.maxBy(i => i._1)._2
    for (i <- 0 until goodScores.length) {
      val goodIndex = goodScores.zipWithIndex.maxBy(i => -i._1)._2
      if (goodScores(goodIndex) < badScores(bestBadIndex) + 1) {
        value.accumulate(goodScores(goodIndex) - badScores(bestBadIndex) - 1)
        GoodBadPiece.addGoodBad(gradient, model, goodCandidates(goodIndex), badCandidates(bestBadIndex))
      }
    }
  }
}

object PieceTest {
  class ModelWithWeightsImpl(model: Model[Variable]) extends Model[Variable] {
    def factors(v: Variable): Iterable[Factor] = throw new Error
    def copy = sys.error("unimpl")
    //def setWeights(t: Tensor) { model.asInstanceOf[LogLinearModel[_, _]].evidenceTemplate.weights := t }
    val weights = new WeightsTensor()
    weights(DummyFamily) = model.asInstanceOf[LogLinearModel[_, _]].evidenceTemplate.weights
    override def weightsTensor = weights
  }

  object DocumentDomain extends CategoricalTensorDomain[String]
  class Document(file: File) extends BinaryFeatureVectorVariable[String] {
    def domain = DocumentDomain
    var label = new Label(file.getParentFile.getName, this)
    // Read file, tokenize with word regular expression, and add all matches to this BinaryFeatureVectorVariable
    "\\w+".r.findAllIn(Source.fromFile(file, "ISO-8859-1").mkString).foreach(regexMatch => this += regexMatch.toString)
  }
  object LabelDomain extends CategoricalDomain[String]
  class Label(name: String, val document: Document) extends LabeledCategoricalVariable(name) {
    def domain = LabelDomain
  }
  def main(args: Array[String]): Unit = {
    // Read data and create Variables
    var docLabels = new classify.LabelList[Label, Document](_.document)
    for (directory <- args) {
      val directoryFile = new File(directory)
      if (!directoryFile.exists) throw new IllegalArgumentException("Directory " + directory + " does not exist.")
      for (file <- new File(directory).listFiles; if (file.isFile)) {
        //println ("Directory "+directory+" File "+file+" documents.size "+documents.size)
        docLabels += new Document(file).label
      }
    }

    // Make a test/train split
    val (testSet, trainSet) = docLabels.shuffle.split(0.5)
    val trainLabels = new classify.LabelList[Label, Document](trainSet, _.document)
    val testLabels = new classify.LabelList[Label, Document](testSet, _.document)

    val loss = LossFunctions.logMultiClassLoss
    // needs to be binary
    val model = new LogLinearModel[Label, Document](_.document, LabelDomain, DocumentDomain)
    val modelWithWeights = new ModelWithWeightsImpl(model)

    //   val forOuter = new la.SingletonBinaryTensor1(2, 0)
    val pieces = trainLabels.map(l => new MultiClassGLMPiece(l.document.value.asInstanceOf[Tensor1], l.target.intValue, loss))

//    val strategy = new HogwildPiecewiseLearner(new SparseL2RegularizedGradientAscent(rate = .01), modelWithWeights)
//    val strategy = new HogwildPiecewiseLearner(new ConfidenceWeighting(modelWithWeights), modelWithWeights)
//    val strategy = new SGDPiecewiseLearner(new StepwiseGradientAscent(rate = .01), modelWithWeights)
//    val strategy = new SGDPiecewiseLearner(new StepwiseGradientAscent(rate = .01), modelWithWeights)
    val strategy = new SGDPiecewiseLearner(new StepwiseGradientAscent(), modelWithWeights)
//    val strategy = new BatchPiecewiseLearner(new SparseL2RegularizedGradientAscent(rate = 10.0 / trainLabels.size), modelWithWeights)

    var totalTime = 0L
    var i = 0
    var perfectAccuracy = false
    while (i < 100 && !strategy.isConverged && !perfectAccuracy) {
      val t0 = System.currentTimeMillis()
      strategy.process(pieces)
      totalTime += System.currentTimeMillis() - t0

      val classifier = new ModelBasedClassifier[Label](model.evidenceTemplate, LabelDomain)

      val testTrial = new classify.Trial[Label](classifier)
      testTrial ++= testLabels

      val trainTrial = new classify.Trial[Label](classifier)
      trainTrial ++= trainLabels

//      println("Weights = " + model.evidenceTemplate.weights)
      println("Train accuracy = " + trainTrial.accuracy)
      println("Test  accuracy = " + testTrial.accuracy)
      println("Total time to train: " + totalTime / 1000.0)
      i += 1
      perfectAccuracy = (trainTrial.accuracy == 1.0 && testTrial.accuracy == 1.0)
    }

    if (strategy.isConverged || perfectAccuracy) println("Converged in " + totalTime / 1000.0)
  }
}