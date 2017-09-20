package test.resources.test_jobs;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import scala.Tuple2;

import com.google.common.collect.Iterables;

import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.sql.SparkSession;

public final class SparkJavaPageRank {

	private static final Pattern SPACES = Pattern.compile("\\s+");

	static void showWarning() {
		String warning = "WARN: This is a naive implementation of PageRank " + "and is given as an example! \n"
				+ "Please use the PageRank implementation found in "
				+ "org.apache.spark.graphx.lib.PageRank for more conventional use.";
		System.err.println(warning);
	}

	private static class Sum implements Function2<Double, Double, Double> {
		@Override
		public Double call(Double a, Double b) {
			return a + b;
		}
	}

	public static void main(String[] args) throws Exception {
		if (args.length < 2) {
			System.err.println("Usage: JavaPageRank <file> <number_of_iterations>");
			System.exit(1);
		}

		showWarning();

		SparkSession spark = SparkSession.builder().appName("JavaPageRank").getOrCreate();

		// Loads in input file. It should be in format of:
		// URL neighbor URL
		// URL neighbor URL
		// URL neighbor URL
		// ...

		JavaRDD<String> lines = spark.read().textFile("swift2d://social_data.lvm/*").javaRDD();

		// Loads all URLs from input file and initialize their neighbors.
		JavaPairRDD<String, Iterable<String>> links = lines.mapToPair(s -> {
			String[] parts = s.split("\\s+");
			return new Tuple2<String, String>(parts[0], parts[1]);
		}).distinct().groupByKey().cache();

		// Loads all URLs with other URL(s) link to from input file and
		// initialize ranks of them to one.
		JavaPairRDD<String, Double> ranks = links.mapValues(rs -> 1.0);

		// Calculates and updates URL ranks continuously using PageRank
		// algorithm.
		for (int current = 0; current < Integer.parseInt(args[1]); current++) {
			// Calculates URL contributions to the rank of other URLs.
			JavaPairRDD<String, Double> contribs = links.join(ranks).values().flatMapToPair(s -> {
				int urlCount = Iterables.size(s._1());
				List<Tuple2<String, Double>> results = new ArrayList<>();
				for (String n : s._1) {
					results.add(new Tuple2<>(n, s._2() / urlCount));
				}
				return results.iterator();
			});

			// Re-calculates URL ranks based on neighbor contributions.
			ranks = contribs.reduceByKey(new Sum()).mapValues(sum -> 0.15 + sum * 0.85);
		}

		// Collects all URL ranks and dump them to console.
		List<Tuple2<String, Double>> output = ranks.collect();
		for (Tuple2<?, ?> tuple : output) {
			System.out.println(tuple._1() + " has rank: " + tuple._2() + ".");
		}

		spark.stop();
	}
}