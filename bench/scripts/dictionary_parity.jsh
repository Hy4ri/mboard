// Desktop inspection of dictionary public behaviour, not a unit/CI test source.
// jshell --class-path <bench lib jars> -R-Dmboard.assets=<assets dir> -R-Dmboard.data=<bench/data>
//        -R-Dmboard.output=<tsv path> dictionary-parity.jsh
import com.m57.mboard.*;
import java.nio.file.*;
import java.util.*;
import java.io.*;
var assets = Path.of(System.getProperty("mboard.assets"));
var data = Path.of(System.getProperty("mboard.data"));
var out = new PrintWriter(Files.newBufferedWriter(Path.of(System.getProperty("mboard.output"))));
for (var code : List.of("en", "ar")) {
    var language = Languages.INSTANCE.byCode(code);
    var dialect = new LinkedHashMap<String,Long>();
    if (code.equals("ar")) for (var line : Files.readAllLines(assets.resolve("jordanian.tsv"))) {
        var p = line.split("\t");
        dialect.put(p[0], p[1].equals("1") ? 60000L : p[1].equals("2") ? 15000L : 4000L);
    }
    var dictionary = Boolean.getBoolean("mboard.binary")
        ? (WordDictionary) WordDictionary.class.getMethod("readBinary", InputStream.class, Language.class, Map.class).invoke(null, Files.newInputStream(assets.resolve("words_" + code + ".tsv.bin")), language, dialect)
        : WordDictionary.read(Files.newBufferedReader(assets.resolve("words_" + code + ".tsv")),language,dialect);
    var prefixes = new TreeSet<String>();
    var words = new TreeSet<String>();
    for (var line : Files.readAllLines(assets.resolve("words_" + code + ".tsv"))) words.add(line.split("\t")[0]);
    if (code.equals("ar")) words.addAll(dialect.keySet());
    for (var word : words) for (int n=1; n<=Math.min(4,word.length()); n++) prefixes.add(word.substring(0,n));
    for (var prefix : prefixes) for (int limit : List.of(1,3,8)) out.println(code + "\tcompletion_off\t" + prefix + "\t" + limit + "\t" + dictionary.completions(prefix,limit,false));
    try (var files = Files.list(data)) {
        for (var path : files.filter(p -> p.getFileName().toString().endsWith("_typos.tsv") && (p.getFileName().toString().startsWith(code + "_") || (code.equals("ar") && p.getFileName().toString().startsWith("jo_")))).sorted().toList()) {
            for (var line : Files.readAllLines(path).subList(1,Files.readAllLines(path).size())) {
                var p=line.split("\t",-1);
                if (p.length>=4) words.add(p[1]);
            }
        }
    }
    for (var word : words) out.println(code + "\tcorrection\t" + word + "\t" + dictionary.correction(word));
}
out.close();
System.out.println("Dictionary public inspection written: " + System.getProperty("mboard.output"));
/exit
