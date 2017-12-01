/*
 * Copyright (c) 2017, Salesforce.com, Inc.
 * All rights reserved.
 */

package com.salesforce.op.stages.impl.regression

import com.salesforce.op.evaluators._
import com.salesforce.op.features.types._
import com.salesforce.op.features.Feature
import com.salesforce.op.stages.impl.regression.RegressionModelsToTry._
import com.salesforce.op.stages.impl.regression.RegressorType.Regressor
import com.salesforce.op.stages.impl.selector.ModelSelectorBaseNames
import com.salesforce.op.stages.impl.tuning.OpCrossValidation
import com.salesforce.op.test.{TestFeatureBuilder, TestSparkContext}
import com.salesforce.op.utils.spark.RichDataset._
import com.salesforce.op.utils.spark.RichMetadata._
import org.apache.spark.ml.linalg.{Vector, Vectors}
import org.apache.spark.ml.regression.{DecisionTreeRegressor, RandomForestRegressor}
import org.apache.spark.ml.tuning.ParamGridBuilder
import org.apache.spark.sql.Encoders
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{Assertions, FlatSpec, Matchers}


@RunWith(classOf[JUnitRunner])
class RegressionModelSelectorTest extends FlatSpec with TestSparkContext {
  val seed = 1234L
  val stageNames = "label_prediction"

  import spark.implicits._

  val rawData: Seq[(Double, Vector)] = List.range(0, 100, 1).map(i =>
    (i.toDouble, Vectors.dense(2 * i, 4 * i)))

  val data = sc.parallelize(rawData).toDF("label", "features")

  val Array(rawLabel: Feature[RealNN]@unchecked, features: Feature[OPVector]@unchecked) =
    TestFeatureBuilder(data, nonNullable = data.schema.fields.map(_.name).toSet)

  val label = rawLabel.copy(isResponse = true)

  val modelSelector = RegressionModelSelector().setInput(label, features)

  Spec[RegressionModelSelector] should "be properly set" in {
    val inputNames = modelSelector.getInputFeatures().map(_.name)
    inputNames should have length 2
    inputNames shouldBe Array(label.name, features.name)
    modelSelector.getOutput().name shouldBe modelSelector.outputName
    the[RuntimeException] thrownBy {
      modelSelector.setInput(label.copy(isResponse = true), features.copy(isResponse = true))
    } should have message "The feature vector should not contain any response features."
  }

  it should "properly select models to try" in {
    modelSelector.setModelsToTry(LinearRegression, RandomForestRegression)

    modelSelector.get(modelSelector.useLR).get shouldBe true
    modelSelector.get(modelSelector.useRF).get shouldBe true
    modelSelector.get(modelSelector.useDT).get shouldBe false
    modelSelector.get(modelSelector.useGBT).get shouldBe false
  }

  it should "set the Linear Regression Params properly" in {
    modelSelector
      .setLinearRegressionMaxIter(10)
      .setLinearRegressionElasticNetParam(0.1)
      .setLinearRegressionFitIntercept(true, false)
      .setLinearRegressionRegParam(0.1, 0.01)
      .setLinearRegressionStandardization(true)
      .setLinearRegressionTol(0.005, 0.0002)

    modelSelector.sparkLR.getMaxIter shouldBe 10
    modelSelector.sparkLR.getElasticNetParam shouldBe 0.1
    modelSelector.sparkLR.getStandardization shouldBe true

    val lrGrid = new ParamGridBuilder().addGrid(modelSelector.sparkLR.fitIntercept, Array(true, false))
      .addGrid(modelSelector.sparkLR.regParam, Array(0.1, 0.01))
      .addGrid(modelSelector.sparkLR.tol, Array(0.005, 0.0002))
      .build

    (modelSelector.lRGrid.build().toSeq zip lrGrid.toSeq)
      .map { case (grid1, grid2) => grid1.toSeq shouldBe grid2.toSeq }
  }

  it should "set the Random Forest Params properly" in {
    modelSelector.setRandomForestMaxBins(34)
      .setRandomForestMaxDepth(7, 8)
      .setRandomForestMinInfoGain(0.1)
      .setRandomForestMinInstancesPerNode(2, 3, 4)
      .setRandomForestSeed(34L)
      .setRandomForestSubsamplingRate(0.4, 0.8)
      .setRandomForestNumTrees(10)

    val sparkRandomForest = modelSelector.sparkRF.asInstanceOf[RandomForestRegressor]

    sparkRandomForest.getMaxBins shouldBe 34
    sparkRandomForest.getMinInfoGain shouldBe 0.1
    sparkRandomForest.getSeed shouldBe 34L
    sparkRandomForest.getNumTrees shouldBe 10

    val rfGrid =
      new ParamGridBuilder().addGrid(sparkRandomForest.maxDepth, Array(7, 8))
        .addGrid(sparkRandomForest.minInstancesPerNode, Array(2, 3, 4))
        .addGrid(sparkRandomForest.subsamplingRate, Array(0.4, 0.8))
        .build

    (modelSelector.rFGrid.build().toSeq zip rfGrid.toSeq)
      .map { case (grid1, grid2) => grid1.toSeq shouldBe grid2.toSeq }
  }

  it should "set the Decision Tree Params properly" in {
    modelSelector.setDecisionTreeMaxBins(34, 44)
      .setDecisionTreeMaxDepth(10)
      .setDecisionTreeMinInfoGain(0.2, 0.5)
      .setDecisionTreeMinInstancesPerNode(5)
      .setDecisionTreeSeed(34L, 56L)

    val sparkDecisionTree = modelSelector.sparkDT.asInstanceOf[DecisionTreeRegressor]
    sparkDecisionTree.getMaxDepth shouldBe 10
    sparkDecisionTree.getMinInstancesPerNode shouldBe 5

    val dtGrid =
      new ParamGridBuilder()
        .addGrid(sparkDecisionTree.maxBins, Array(34, 44))
        .addGrid(sparkDecisionTree.minInfoGain, Array(0.2, 0.5))
        .addGrid(sparkDecisionTree.seed, Array(34L, 56L))
        .build

    (modelSelector.dTGrid.build().toSeq zip dtGrid.toSeq)
      .map { case (grid1, grid2) => grid1.toSeq shouldBe grid2.toSeq }
  }

  it should "set the Gradient Boosted Tree Params properly" in {
    modelSelector.setGradientBoostedTreeMaxBins(34, 44)
      .setGradientBoostedTreeMaxDepth(10)
      .setGradientBoostedTreeMinInfoGain(0.2, 0.5)
      .setGradientBoostedTreeMinInstancesPerNode(5)
      .setGradientBoostedTreeSeed(34L, 56L)
      .setGradientBoostedTreeStepSize(0.5)
      .setGradientBoostedTreeLossType(LossType.Squared, LossType.Absolute)

    modelSelector.sparkGBT.getMaxDepth shouldBe 10
    modelSelector.sparkGBT.getMinInstancesPerNode shouldBe 5
    modelSelector.sparkGBT.getStepSize shouldBe 0.5

    val dtGrid =
      new ParamGridBuilder()
        .addGrid(modelSelector.sparkGBT.maxBins, Array(34, 44))
        .addGrid(modelSelector.sparkGBT.minInfoGain, Array(0.2, 0.5))
        .addGrid(modelSelector.sparkGBT.seed, Array(34L, 56L))
        .addGrid(modelSelector.sparkGBT.lossType, Array(LossType.Squared, LossType.Absolute).map(_.sparkName))
        .build

    (modelSelector.gBTGrid.build().toSeq zip dtGrid.toSeq)
      .map { case (grid1, grid2) => grid1.toSeq shouldBe grid2.toSeq }
  }

  it should "set the cross validation params correctly" in {
    val crossValidator = new OpCrossValidation[Regressor](numFolds = 3,
      evaluator = Evaluators.Regression.rmse(), seed = 10L)

    crossValidator.validationParams shouldBe Map(
      "metric" -> OpMetricsNames.rootMeanSquaredError,
      "numFolds" -> 3,
      "seed" -> 10L
    )
  }

  it should "set the data splitting params correctly" in {
    modelSelector.splitter.get.setReserveTestFraction(0.1).setSeed(11L)
    modelSelector.splitter.get.getSeed shouldBe 11L
    modelSelector.splitter.get.getReserveTestFraction shouldBe 0.1
  }

  it should "split into training and test" in {

    implicit val vectorEncoder: org.apache.spark.sql.Encoder[Vector] = ExpressionEncoder()
    implicit val e1 = Encoders.tuple(Encoders.scalaDouble, vectorEncoder)

    val (train, test) = modelSelector.setInput(label, features)
      .splitter.get.randomSplit(data.as[(Double, Vector)], 0.8)

    val trainCount = train.count()
    val testCount = test.count()
    val totalCount = rawData.length

    assert(math.abs(testCount - 0.2 * totalCount) <= 3)
    assert(math.abs(trainCount - 0.8 * totalCount) <= 3)

    trainCount + testCount shouldBe totalCount
  }

  it should "fit and predict" in {
    val testEstimator =
      RegressionModelSelector
        .withCrossValidation(numFolds = 4, validationMetric = Evaluators.Regression.mse(), seed = 10L)
        .setModelsToTry(LinearRegression, RandomForestRegression)
        .setLinearRegressionElasticNetParam(0, 0.5, 1)
        .setLinearRegressionMaxIter(10, 100)
        .setLinearRegressionSolver(Solver.LBFGS)
        .setRandomForestMaxDepth(2, 10)
        .setRandomForestNumTrees(10)
        .setInput(label, features)

    val model = testEstimator.fit(data)
    val pred = model.getOutput()

    // evaluation metrics from test set should be in metadata
    val metaData = model.getMetadata().getSummaryMetadata().getMetadata(ModelSelectorBaseNames.HoldOutEval)
    // No evaluator passed so only the default one is there
    val evaluatorMetricName = testEstimator.trainTestEvaluators.head.name

    RegressionEvalMetrics.values.foreach(metric =>
      assert(metaData.contains(s"(${OpEvaluatorNames.regression})_${metric.entryName}"),
        s"Metric ${metric.entryName} is not present in metadata: " + metaData.json)
    )

    val transformedData = model.transform(data)
    val justScores = transformedData.collect(pred)

    assertScores(justScores, transformedData.collect(label))
  }

  it should "fit and predict with a train validation split even if there is no split between training and test" in {
    val testEstimator =
      RegressionModelSelector
        .withTrainValidationSplit(
          dataSplitter = None,
          trainRatio = 0.8,
          validationMetric = Evaluators.Regression.r2(),
          seed = 10L
        )
        .setModelsToTry(LinearRegression, RandomForestRegression)
        .setLinearRegressionElasticNetParam(0.5)
        .setLinearRegressionMaxIter(10, 100)
        .setLinearRegressionSolver(Solver.Auto)
        .setRandomForestMaxDepth(2, 10)
        .setInput(label, features)
    val pred = testEstimator.getOutput()
    val transformedData = testEstimator.fit(data).transform(data)
    val justScores = transformedData.collect(pred)

    assertScores(justScores, transformedData.collect(label))
  }

  it should "fit and predict correctly with MeanAbsoluteError used" in {
    val testEstimator =
      RegressionModelSelector
        .withCrossValidation(numFolds = 4, validationMetric = Evaluators.Regression.mae(), seed = 11L)
        .setModelsToTry(LinearRegression, RandomForestRegression)
        .setLinearRegressionElasticNetParam(0, 0.5, 1)
        .setLinearRegressionSolver(Solver.LBFGS)
        .setRandomForestMaxDepth(2, 10)
        .setRandomForestNumTrees(10)
        .setInput(label, features)
    val pred = testEstimator.getOutput()
    val transformedData = testEstimator.fit(data).transform(data)
    val justScores = transformedData.collect(pred)

    assertScores(justScores, transformedData.collect(label))
  }

  it should "fit and predict correctly with a custom metric used" in {

    val medianAbsoluteError = Evaluators.Regression.custom(
      metricName = "median absolute error",
      isLargerBetter = false,
      evaluateFn = ds => {
        val medAE = ds.map { case (lbl, prediction) => math.abs(prediction - lbl) }
        val median = medAE.stat.approxQuantile(medAE.columns.head, Array(0.5), 0.25)
        median.head
      }
    )
    val testEstimator =
      RegressionModelSelector
        .withCrossValidation(numFolds = 4, validationMetric = medianAbsoluteError, seed = 11L)
        .setModelsToTry(LinearRegression, RandomForestRegression)
        .setLinearRegressionElasticNetParam(0, 0.5, 1)
        .setLinearRegressionSolver(Solver.Normal)
        .setRandomForestMaxDepth(2, 10)
        .setRandomForestNumTrees(10)
        .setInput(label, features)
    val pred = testEstimator.getOutput()
    val transformedData = testEstimator.fit(data).transform(data)
    val justScores = transformedData.collect(pred)

    assertScores(justScores, transformedData.collect(label))
  }

  it should "fit correctly with a custom metric used as holdOut evaluator" in {

    val medianAbsoluteError = Evaluators.Regression.custom(
      metricName = "median absolute error",
      isLargerBetter = false,
      evaluateFn = ds => {
        val medAE = ds.map { case (lbl, prediction) => math.abs(prediction - lbl) }
        val median = medAE.stat.approxQuantile(medAE.columns.head, Array(0.5), 0.25)
        median.head
      }
    )
    val testEstimator =
      RegressionModelSelector
        .withCrossValidation(numFolds = 4, validationMetric = medianAbsoluteError,
          trainTestEvaluators = Seq(medianAbsoluteError), seed = 11L)
        .setModelsToTry(LinearRegression, RandomForestRegression)
        .setLinearRegressionElasticNetParam(0, 0.5, 1)
        .setLinearRegressionSolver(Solver.Normal)
        .setRandomForestMaxDepth(2, 10)
        .setRandomForestNumTrees(10)
        .setInput(label, features)
    val model = testEstimator.fit(data)

    // checking trainingEval & holdOutEval metrics
    val metaData = model.getMetadata().getSummaryMetadata()
    val trainMetaData = metaData.getMetadata(ModelSelectorBaseNames.TrainingEval)
    val holdOutMetaData = metaData.getMetadata(ModelSelectorBaseNames.HoldOutEval)

    testEstimator.trainTestEvaluators.foreach {
      case evaluator: OpRegressionEvaluator => {
        RegressionEvalMetrics.values.foreach(metric =>
          Seq(trainMetaData, holdOutMetaData).foreach(
            metadata => assert(metadata.contains(s"(${OpEvaluatorNames.regression})_${metric.entryName}"),
              s"Metric ${metric.entryName} is not present in metadata: " + metadata.json)
          )
        )
      }
      case evaluator: OpRegressionEvaluatorBase[_] => {
        println(metaData.json)
        Seq(trainMetaData, holdOutMetaData).foreach(
          metadata =>
            assert(metadata.contains(s"(${evaluator.name})_${evaluator.name}"),
              s"Single Metric evaluator ${evaluator.name} is not present in metadata: " + metadata.json)
        )
      }
    }
  }

  private def assertScores(scores: Array[RealNN], labels: Array[RealNN]) = {
    val res = scores.zip(labels).map { case (score: RealNN, label: RealNN) => math.abs(score.v.get - label.v.get) }.sum
    assert(res <= scores.length, "prediction failed")
  }
}
