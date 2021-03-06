### Code Analyzer for Spark Jobs (Java)

This project is an example of the type of code analyzers that can be integrated in [Crystal](https://github.com/Crystal-SDS) for optimizing analytics data access to object storage via software-defined storage mechanisms. In particular, the objective of this project is to analyze the code of an input Spark job (Java) and to identify those operations performed on RDDs (and many of its subclasses) that can be delegated for execution to the storage side (thanks to a new [Storlet](https://github.com/Crystal-SDS/filter-samples/tree/master/Storlet_lambda_pushdown)). That is, operations like filter(), map() or reduce() are migrated and executed at the storage side for reducing the amount of data transferred among clusters and even obtaining faster application execution times.

### Lifecycle

The lifecycle of Crystal's code analyzers is as follows:
1. The administrator deploys a code analyzer executable via the [Crystal API](https://github.com/Crystal-SDS/controller).
2. The administrator deploys the [filter/Storlet](https://github.com/Crystal-SDS/filter-samples/tree/master/Storlet_lambda_pushdown) at the storage side to execute code on data object flows.
3. The administrator defines a policy specifying that the Spark jobs of a certain tenant will be associated to the code analyzer.
4. A user submits his Spark job (e.g., WordCount.java) via the Crystal API.
5. The Crystal API inputs the job to the code analyzer.
6. Depending on the output of the code analyzer, the Crystal API will submit to the Spark cluster the original job or a new version of in which parts of the computations are pushed-down to the storage filter/Storlet.

### A Simple Example

Let us show which is the actual point of a code analyzer. To this end, we make use of a word count Spark job:

```java
//Original job submitted by the user
JavaRDD<String> textFile = sc.textFile("swift2d://data1.lvm/hamlet.txt");
JavaPairRDD<String, Integer> counts = textFile
    .flatMap(s -> Arrays.asList(s.split(" ")).iterator())
    .map(word -> word.replaceAll("[^a-zA-Z]", "").toLowerCase().trim())
    .mapToPair(word -> new Tuple2<>(word, 1))
    .reduceByKey((a, b) -> a + b);
counts.saveAsTextFile("hdfs://...");
```
If we execute this job as usual in a compute cluster, Spark will first require to *ingest the whole dataset from Swift* to split lines into words, clean odd characters, create word/count pairs and then execute a final sum of word occurrences grouping words by key.

### The Compute Side: Code Analyzer

The idea behind a code analyzer in Crystal is that some of these processing steps can be efficienlty done in a *stream fashion* at the storage side in parallel by storage nodes, just when data is being served to Spark from Swift. To show an example of this, this project can automatically transform the original job into the following one:

```java
//Modified job code output by the code analyzer
JavaRDD<String> textFile = sc.textFile("swift2d://data1.lvm/hamlet.txt"); 
JavaPairRDD<String, Integer> counts = textFile
    .mapToPair(word -> new Tuple2<String, Integer>(word.split("=")[0], Integer.valueOf(word.split("=")[1])))
    .reduceByKey((a, b) -> a + b); 
counts.saveAsTextFile("swift2d://data1.lvm/hamlet_result.txt");
```

Conversely to the original job code, the modified version expects to receive a dataset which is composed by <word,count> pairs and then it performs a final reduceByKey operation on that data. The reason for this modification in the code is that the code analyzer told the storage side to execute the following parts of the job:

```java
//Lambda functions delegated to Swift, compiled at runtime and executed in stream as data is served
flatMap(s -> Arrays.stream(s.split(" ")))
map(word -> word.replaceAll("[^a-zA-Z]", "").toLowerCase().trim())
map(word -> new SimpleEntry<String, Integer>(word, 1))
collect(java.util.stream.Collectors.groupingBy(SimpleEntry<String, Integer>::getKey, java.util.stream.Collectors.counting()))
```

Therefore, the task of splitting and cleaning words is completelly delegated to storage nodes. Even more, each storage node does a partial word count of the data object that it serves. This is why the reduceByKey operation is still required at the Spark side, as the "partial wordcounts" performed by each storage node are finally aggregated by Spark. 

In the particular case of "WordCount" on the "Hamlet" text file, *we reduce the data transfer from Swift to Spark in over 76%*.

### The Storage Side: Lambda Pushdown Storlet 

The Lambda Pushdown Storlet is a storage filter that can i) convert byte streams into Java8 streams, ii) receive as input parameters Java 8 lambda functions, and iii) compile them at runtime and execute them on the data stream. To wit, an administrator can parameterize the storlet from the dashboard with lambda functions that will be compiled and executed upon a GET/PUT request. 

In general, the compilation time costs of simple lambdas are acceptable (1ms to 3ms), but they increase as the complexity of lambdas grow. There are two main optimizations implemented in the Storlet by design to improve performance:

* The encoding from byte-level stream to a Java 8 stream is only perform when there are lambdas to execute.
* The storlet contains a cache for lambdas and collectors, which means that only "new" functions pay the costs of compilation. In fact, it is common in many analytics workloads that jobs are executed periodically; therefore, for such jobs the compilation costs disappear from the second execution onwards.

### How to Do a Real Test

Doing real test with the whole system is relatively simple. Here are the steps to follow:

1. Download and initiate the [Crystal development VM that we provide](ftp://ast2-deim.urv.cat/s2caio_vm).
2. Download and build (ant build.xml) the [Lambda Pushdown Storlet](https://github.com/Crystal-SDS/filter-samples/tree/master/Storlet_lambda_pushdown) and deploy the Storlet in Crystal via the dashboard.
3. Download Spark (v2) from the [official webpage](http://spark.apache.org/downloads.html).
4. Download and configure Stocator as [Spark connector for Swift](https://github.com/SparkTC/stocator) (optional, but recommended). 
5. Export the job analyzer project as a .jar with the dependencies included (e.g., in Eclipse -> Export -> Runnable JAR -> and use as main SparkJavaAnalyzerExecutor.java)

With this, you have the environment almost prepared to execute. Before executing some real stuff, you need to:

6. Add a text file or sample data to a container (e.g., data1 container in the Crystal VM).
7. Create a policy in Crystal associating the container with the Lambda Pushdown Storlet. By doing this, the GET requests on the data objects of this container will be processed by the Storlet.

The last step is to do the actual execution. To this end, there is a python script that performs the complete lifecycle:

```bash
python SparkJavaJobAnalyzerExecutor.py /home/user/Desktop/SparkJavaJobAnalyzer.jar /home/user/Desktop/SparkJavaWordCount.java
```
The python script i) gets as input a Spark job, ii) executes the code analyzer JAR that you created, iii) sets the output lambdas to the Lambda Pushdown Storlet, iv) compiles/packages the Spark job, and v) submits the job to Spark. To enable the Python script to work properly, please set the constants of the beggining of the script code (it is already configured to work with the Crystal VM, but things like IPs and the location of Spark depend on your environment).   

### Practical Considerations and Limitations

As a prototype, this code is able to handle a certain spectrum of cases/code embedded within Spark jobs, but it is not a complete analyzer that copes with all possible cases. There are important limitations that a developer must be aware of: i) We only tested cases in which the lambdas contain all the logic for execution (i.e., we have not worked on handling references from lambdas to external code/functions), ii) The "types" of data that can be managed at the storage side are standard ones (String, Integer, Long, Lists, SimpleEntry,..) but not custom ones created at the Spark application (this would require the storage side not to only compile functions, but also types as well).  

### Conclusion

The idea is that Crystal can be a platform to support multiple job analyzers to optimize a variety of Big Data analytics frameworks apart from Spark. It is important to note that the jobs that will benefit most from job analyzers are "data intensive"; that is, jobs that operate on large amounts of data and require transformations on it (iterative jobs that compute on small datasets will obtain small or no gains, as their performance barrier is not IO). 








