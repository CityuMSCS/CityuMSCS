import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.StringTokenizer;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;

public class Bow {
    private final static String[] top100Word = { "the", "be", "to", "of", "and", "a", "in", "that", "have", "i",
            "it", "for", "not", "on", "with", "he", "as", "you", "do", "at", "this", "but", "his", "by", "from",
            "they", "we", "say", "her", "she", "or", "an", "will", "my", "one", "all", "would", "there", "their",
            "what", "so", "up", "out", "if", "about", "who", "get", "which", "go", "me", "when", "make", "can",
            "like", "time", "no", "just", "him", "know", "take", "people", "into", "year", "your", "good", "some",
            "could", "them", "see", "other", "than", "then", "now", "look", "only", "come", "its", "over", "think",
            "also", "back", "after", "use", "two", "how", "our", "work", "first", "well", "way", "even", "new",
            "want", "because", "any", "these", "give", "day", "most", "us" };

    private final static int N_TEST_FILE = 10;
    private static BufferedReader br;
    private static BufferedWriter bw;
    private static LinkedHashMap<String,String> hm = new LinkedHashMap<String,String>();

    public static class TokenizerMapper extends Mapper<Object, Text, Text, IntWritable>{
        private final static IntWritable one = new IntWritable(1);
        private Text word = new Text();

        public void map(Object key, Text value,Context context) throws IOException, InterruptedException{

            // filter special character and convert to lower case
            String clean_str = value.toString().replaceAll("[^a-zA-Z0-9]", " ");
            clean_str = clean_str.toLowerCase();

            StringTokenizer itr = new StringTokenizer(clean_str);
            while (itr.hasMoreTokens()) {
                String token = itr.nextToken();

                // 只输出在top100Word中的单词
                if(Arrays.asList(top100Word).contains(token)){
                    word.set(token);
                    context.write(word, one);
                }
            }
        }
    }

    public static class IntSumReducer extends Reducer<Text, IntWritable, Text, IntWritable>{
        private IntWritable result = new IntWritable();

        public void reduce(Text key, Iterable<IntWritable> values, Context context) throws IOException, InterruptedException{
            int sum = 0;
            for (IntWritable value : values) {
                sum += value.get();
            }
            result.set(sum);
            context.write(key, result);
        }
    }

    private static String getBow(FileSystem fs, String file_name, String file_path) {
        try {
            br = new BufferedReader(new InputStreamReader(fs.open(new Path(file_path+"/part-r-00000"))));
            String line = null;

            // get words
            while ((line = br.readLine()) != null) {
                String[] pair = line.split("	");
                hm.put(pair[0], pair[1]);
            }
            br.close();

            // convert hashmap to string
            ArrayList<String> values = new ArrayList<String>(hm.values());
            String output = String.join(", ", values);
            output = file_name+'	'+output;

            return output;

        } catch (IOException e) {
            System.err.println("Caught exception while loading result.");
        }
        return "";
    }

    public static void main(String[] args) throws IOException, ClassNotFoundException, InterruptedException{
        //参数个数判断
        if (args.length != 2) {
            System.err.println("Argument: <in> <out>");
            System.exit(0);
        }

        //文件系统与路径变量
        String inputDir= "";
        String fileName = "";
        String outputDir= "";
        String outputFile= "";

        if(!args[0].substring(args[0].length() - 1).equals("/")){
            inputDir = args[0]+"/";
        }else {
            inputDir = args[0];
        }
        if(!args[1].substring(args[1].length() - 1).equals("/")){
            outputDir = args[1]+"/";
        }else {
            outputDir = args[1];
        }

        //获取文件系统路径
        Configuration conf = new Configuration();
        FileSystem fs = null;
        if (inputDir.startsWith("s3://")) {
            String bucketName = inputDir.split("/")[2];  // 通过分割 S3 路径获取桶名
            String s3Path = "s3a://" + bucketName;
            try {
                fs = FileSystem.get(new URI(s3Path), conf);
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }
        }
        else {
            fs = FileSystem.get(conf);
        }


        String[] bow = new String[N_TEST_FILE];
        //主要任务逻辑
        for (int i=0; i<N_TEST_FILE; i++){
            //初始化哈希表，键值对为"字典字段:个数"
            for(String word: top100Word) {
                hm.put(word,"0");
            }

            //输出目录
            fileName = "file"+((i+1 != N_TEST_FILE) ? "0" : "")+String.valueOf(i+1);
            outputFile = outputDir+fileName;
            //MapReduce执行
            Job job = Job.getInstance(conf, "bow");
            job.setJarByClass(Bow.class);
            job.setMapperClass(TokenizerMapper.class);
            job.setCombinerClass(IntSumReducer.class);
            job.setReducerClass(IntSumReducer.class);
            job.setOutputKeyClass(Text.class);
            job.setNumReduceTasks(1);
            job.setOutputValueClass(IntWritable.class);
            FileInputFormat.addInputPath(job, new Path(inputDir+fileName+".txt"));
            FileOutputFormat.setOutputPath(job, new Path(outputFile));
            job.waitForCompletion(true);

            //合并结果

            bow[i] = getBow(fs, fileName, outputFile);

            System.out.println("Finished: "+fileName);
        }

        // create file on hdfs
        OutputStream fsdos = fs.create(new Path(outputDir+"Q2-output.txt"),true);

        bw = new BufferedWriter(new OutputStreamWriter(fsdos));
        for(String line: bow) {
            bw.write(line+'\n');
        }

        bw.close();
        fsdos.close();
        fs.close();
    }
}
