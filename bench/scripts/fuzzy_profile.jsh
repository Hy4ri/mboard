// Real-dictionary cold/warm fuzzy lookup profiling. Desktop JVM only, not Android frame latency.
// Run with bench lib jars, -R-Dmboard.assets=<same assets dir> -R-Dmboard.output=<JSON path>.
import com.m57.mboard.*;
import org.json.*;
import java.nio.file.*;
import java.util.*;
var assets = Path.of(System.getProperty("mboard.assets"));
var report = new JSONObject();
for (var code : List.of("en", "ar")) {
    var language=Languages.INSTANCE.byCode(code);
    var dialect=new LinkedHashMap<String,Long>();
    if(code.equals("ar")) for(var line:Files.readAllLines(assets.resolve("jordanian.tsv"))) {
        var p=line.split("\t");dialect.put(p[0],p[1].equals("1")?60000L:p[1].equals("2")?15000L:4000L);
    }
    var d=WordDictionary.read(Files.newBufferedReader(assets.resolve("words_"+code+".tsv")),language,dialect);
    var prefixes=code.equals("en")?List.of("unfortunatl","beautiiful","becuase","goign","teh","someht","definat","thinkn"):List.of("المردسة","حبيي","الصابة","خياىر","بتأثسر","اجكع","مكتبت","بالبي");
    var samples=new long[4000];long checksum=0L;
    long coldStart=System.nanoTime();
    for(var p:prefixes) checksum=31L*checksum+d.fuzzyCompletions(p,6,code.equals("ar")).hashCode();
    long coldUs=(System.nanoTime()-coldStart)/1000L;
    for(int i=0;i<500;i++) d.fuzzyCompletions(prefixes.get(i%prefixes.size()),6,code.equals("ar"));
    for(int i=0;i<samples.length;i++) {
        long start=System.nanoTime();var result=d.fuzzyCompletions(prefixes.get(i%prefixes.size()),6,code.equals("ar"));
        samples[i]=(System.nanoTime()-start)/1000L;checksum=31L*checksum+result.hashCode();
    }
    Arrays.sort(samples);
    report.put(code,new JSONObject().put("queries",samples.length).put("prefixes",prefixes.size()).put("cold_total_us",coldUs).put("warm_p50_us",samples[samples.length/2]).put("warm_p95_us",samples[(int)(samples.length*.95)]).put("warm_p99_us",samples[(int)(samples.length*.99)]).put("warm_max_us",samples[samples.length-1]).put("result_checksum",Long.toString(checksum)));
}
Files.writeString(Path.of(System.getProperty("mboard.output")),report.toString(2)+"\n");
System.out.println(report.toString(2));
/exit
