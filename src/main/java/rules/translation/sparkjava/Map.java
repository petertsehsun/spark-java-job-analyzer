package main.java.rules.translation.sparkjava;

import main.java.graph.GraphNode;
import main.java.rules.LambdaRule;

public class Map implements LambdaRule {

	@Override
	public void applyRule(GraphNode graphNode) {
		String replacement = graphNode.getLambdaSignature();
		//Change the typical Tuple2 class for pairs to a common Java class
		replacement = replacement.replace("Tuple2", "SimpleEntry");
		replacement = replacement.replace("_1", "getKey()");
		replacement = replacement.replace("_2", "getValue()");
		graphNode.setCodeReplacement(replacement);
	}

}
