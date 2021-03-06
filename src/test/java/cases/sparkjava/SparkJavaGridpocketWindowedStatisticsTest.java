package test.java.cases.sparkjava;

import java.nio.file.Paths;

import org.json.simple.JSONObject;

import junit.framework.TestCase;
import main.java.analyzer.SparkJavaJobAnalyzer;
import main.java.utils.Utils;

public class SparkJavaGridpocketWindowedStatisticsTest extends TestCase{	
	
	protected final String TEST_PATH = Paths.get("").toAbsolutePath().toString()+
			"/src/test/resources/";
	
	public void testJob() {
		/*
		 * STEP 2: Execute pushdown analysis on the analytics task
		 */     
        SparkJavaJobAnalyzer jobAnalyzer = new SparkJavaJobAnalyzer();        
        // visit and print the methods names
        JSONObject pushdownAnalysisResult = jobAnalyzer.analyze(
        		this.TEST_PATH + "/test_jobs/sparkjava/SparkJavaGridpocketWindowedStatistics.java");
        
        System.out.println("LAMBDAS TO MIGRATE FROM SPARK:");
        System.out.println(Utils.getLambdasToMigrate(pushdownAnalysisResult));
        System.out.println("MODIFIED SPARK JOB:");
        System.out.println(Utils.getModifiedJobCode(pushdownAnalysisResult));
	}	
}
