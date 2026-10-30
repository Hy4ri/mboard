// Trains the tiny next-word reranker (tied embeddings + one ReLU layer over the last two words).
// No numpy/torch exist in the dev shell, so this is a dependency-free single-file Java program:
//
//   java bench/lm/TrainReranker.java --lang en --tables bench/lm/out_en --out app/src/main/assets/rerank_en.bin
//
// Training data is ONLY the count tables built from the licensed corpora (see SOURCES.md): next_<l>.tsv and
// context_<l>.tsv (+ unigram_<l>.tsv for the vocabulary). Those tables already exclude every bench/data
// evaluation line and held-out subtitle window (build_ngrams.py). Pass --tables bench/lm/out_jo_split for
// Jordanian; never out_jo (full Shami). Fixed seed: output is reproducible for the same arguments/thread count.
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class TrainReranker {
    static final int UNK = 0, BOS = 1, PAD = 2, FIRST = 3;
    static int V, D, H;

    static String key(String w) {
        StringBuilder b = new StringBuilder();
        for (char c : w.toLowerCase(Locale.ROOT).toCharArray()) {
            if ((c >= 0x64B && c <= 0x65F) || c == 0x670 || c == 0x640 || c == '\'' || c == 0x2019) continue;
            b.append(c);
        }
        return b.toString();
    }

    static final class Row {
        int a, b;
        int[] t;
        float[] q;
        float w;
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> o = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) o.put(args[i].substring(2), args[i + 1]);
        String lang = o.get("lang");
        String dir = o.get("tables");
        String out = o.get("out");
        int vocab = Integer.parseInt(o.getOrDefault("vocab", "10000"));
        D = Integer.parseInt(o.getOrDefault("dim", "48"));
        H = Integer.parseInt(o.getOrDefault("hidden", "128"));
        int epochs = Integer.parseInt(o.getOrDefault("epochs", "4"));
        int threads = Integer.parseInt(o.getOrDefault("threads", "4"));
        long seed = Long.parseLong(o.getOrDefault("seed", "57485"));
        float lr = Float.parseFloat(o.getOrDefault("lr", "0.05"));
        double wpow = Double.parseDouble(o.getOrDefault("weightpow", "0.5"));

        // Vocabulary: most frequent keys of the unigram table (apostrophes folded like the runtime matchKey).
        Map<String, Long> uni = new HashMap<>();
        try (BufferedReader r = reader(dir + "/unigram_" + lang + ".tsv")) {
            String l;
            while ((l = r.readLine()) != null) {
                String[] p = l.split("\t");
                if (p.length < 2) continue;
                String k = key(p[0].trim());
                if (!k.isEmpty()) uni.merge(k, Long.parseLong(p[1].trim()), Long::sum);
            }
        }
        List<Map.Entry<String, Long>> ents = new ArrayList<>(uni.entrySet());
        ents.sort((x, y) -> y.getValue().equals(x.getValue()) ? x.getKey().compareTo(y.getKey()) : Long.compare(y.getValue(), x.getValue()));
        List<String> words = new ArrayList<>();
        Map<String, Integer> id = new HashMap<>();
        for (Map.Entry<String, Long> e : ents) {
            if (words.size() >= vocab - FIRST) break;
            if (e.getKey().equals("<s>") || e.getKey().contains("\t")) continue;
            id.put(e.getKey(), FIRST + words.size());
            words.add(e.getKey());
        }
        V = FIRST + words.size();
        id.put("<s>", BOS);

        List<Row> rows = new ArrayList<>();
        for (String name : new String[] {"next_", "context_"}) {
            try (BufferedReader r = reader(dir + "/" + name + lang + ".tsv")) {
                String l;
                while ((l = r.readLine()) != null) {
                    String[] p = l.split("\t");
                    if (p.length < 3) continue;
                    String[] ctx = key(p[0].trim()).isEmpty() ? new String[0] : p[0].trim().split(" ");
                    if (ctx.length == 0 || ctx.length > 2) continue;
                    Row row = new Row();
                    int c1 = lookup(id, key(ctx[ctx.length - 1]));
                    if (ctx.length == 1) {
                        boolean start = ctx[0].equals("<s>");
                        row.a = start ? PAD : BOS;
                        row.b = start ? BOS : c1;
                    } else {
                        row.a = lookup(id, key(ctx[0]));
                        row.b = c1;
                    }
                    long total = Long.parseLong(p[1].trim());
                    Map<Integer, Double> tg = new TreeMap<>();
                    double sum = 0;
                    for (int i = 2; i < p.length; i++) {
                        int colon = p[i].lastIndexOf(':');
                        if (colon <= 0) continue;
                        double c = Double.parseDouble(p[i].substring(colon + 1));
                        tg.merge(lookup(id, key(p[i].substring(0, colon))), c, Double::sum);
                        sum += c;
                    }
                    if (tg.isEmpty() || sum <= 0) continue;
                    row.t = new int[tg.size()];
                    row.q = new float[tg.size()];
                    int n = 0;
                    for (Map.Entry<Integer, Double> e : tg.entrySet()) {
                        row.t[n] = e.getKey();
                        row.q[n++] = (float) (e.getValue() / sum);
                    }
                    row.w = (float) Math.pow(total, wpow);
                    rows.add(row);
                }
            }
        }
        double mw = 0;
        for (Row r : rows) mw += r.w;
        mw /= rows.size();
        for (Row r : rows) r.w /= (float) mw;
        System.err.printf("lang=%s V=%d D=%d H=%d rows=%d%n", lang, V, D, H, rows.size());

        Random rnd = new Random(seed);
        float[] E = new float[V * D], W1 = new float[H * 2 * D], b1 = new float[H], W2 = new float[D * H], b2 = new float[D], bias = new float[V];
        for (int i = 0; i < E.length; i++) E[i] = (float) (rnd.nextGaussian() * 0.1);
        float s1 = (float) Math.sqrt(2.0 / (2 * D)), s2 = (float) Math.sqrt(1.0 / H);
        for (int i = 0; i < W1.length; i++) W1[i] = (float) (rnd.nextGaussian() * s1);
        for (int i = 0; i < W2.length; i++) W2[i] = (float) (rnd.nextGaussian() * s2);
        float[][] P = {E, W1, b1, W2, b2, bias};
        float[][] G2 = new float[P.length][];
        for (int i = 0; i < P.length; i++) G2[i] = new float[P[i].length];

        ExecutorService ex = Executors.newFixedThreadPool(threads);
        int batch = 256;
        int[] order = new int[rows.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        float[][][] grads = new float[threads][P.length][];
        for (int t = 0; t < threads; t++) for (int i = 0; i < P.length; i++) grads[t][i] = new float[P[i].length];
        for (int ep = 0; ep < epochs; ep++) {
            for (int i = order.length - 1; i > 0; i--) {
                int j = rnd.nextInt(i + 1);
                int tmp = order[i];
                order[i] = order[j];
                order[j] = tmp;
            }
            double[] loss = new double[threads];
            double[] wsum = new double[threads];
            long t0 = System.currentTimeMillis();
            for (int s = 0; s + batch <= order.length; s += batch) {
                final int start = s;
                List<Future<?>> fs = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    final int th = t;
                    fs.add(ex.submit(() -> {
                        for (float[] g : grads[th]) Arrays.fill(g, 0f);
                        Work w = new Work(P, grads[th]);
                        for (int k = th; k < batch; k += threads) {
                            Row r = rows.get(order[start + k]);
                            loss[th] += w.step(r);
                            wsum[th] += r.w;
                        }
                    }));
                }
                for (Future<?> f : fs) f.get();
                for (int i = 0; i < P.length; i++) {
                    float[] p = P[i], g2 = G2[i];
                    for (int j = 0; j < p.length; j++) {
                        float g = 0;
                        for (int t = 0; t < threads; t++) g += grads[t][i][j];
                        if (g == 0f) continue;
                        g /= batch;
                        g2[j] += g * g;
                        p[j] -= lr * g / (float) (Math.sqrt(g2[j]) + 1e-8);
                    }
                }
            }
            double l = 0, ws = 0;
            for (int t = 0; t < threads; t++) {
                l += loss[t];
                ws += wsum[t];
            }
            System.err.printf("epoch %d weighted-xent %.4f  %.0fs%n", ep + 1, l / ws, (System.currentTimeMillis() - t0) / 1000.0);
        }
        ex.shutdown();

        // int8 export: one symmetric scale per tensor; biases stay float.
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(bo);
        d.writeBytes("MRK1");
        d.writeInt(V);
        d.writeInt(D);
        d.writeInt(H);
        d.writeInt(words.size());
        for (String w : words) d.writeUTF(w);
        for (float[] t : new float[][] {E, W1, W2}) {
            float m = 0;
            for (float v : t) m = Math.max(m, Math.abs(v));
            float sc = m / 127f;
            d.writeFloat(sc);
            for (float v : t) d.writeByte(Math.round(v / sc));
        }
        for (float[] t : new float[][] {b1, b2}) for (float v : t) d.writeFloat(v);
        float mb = 0;
        for (float v : bias) mb = Math.max(mb, Math.abs(v));
        float sb = mb / 127f;
        d.writeFloat(sb);
        for (float v : bias) d.writeByte(Math.round(v / sb));
        d.flush();
        try (FileOutputStream f = new FileOutputStream(out)) {
            f.write(bo.toByteArray());
        }
        System.err.println("wrote " + out + " " + bo.size() + " bytes");
    }

    static int lookup(Map<String, Integer> id, String k) {
        Integer v = id.get(k);
        return v == null ? UNK : v;
    }

    static BufferedReader reader(String p) throws IOException {
        return new BufferedReader(new InputStreamReader(new FileInputStream(p), StandardCharsets.UTF_8));
    }

    static final class Work {
        final float[] E, W1, b1, W2, b2, bias;
        final float[] gE, gW1, gb1, gW2, gb2, gBias;
        final float[] x = new float[2 * D], z = new float[H], h = new float[H], oo = new float[D], lg = new float[V];
        final float[] dO = new float[D], dH = new float[H], dX = new float[2 * D];

        Work(float[][] P, float[][] G) {
            E = P[0]; W1 = P[1]; b1 = P[2]; W2 = P[3]; b2 = P[4]; bias = P[5];
            gE = G[0]; gW1 = G[1]; gb1 = G[2]; gW2 = G[3]; gb2 = G[4]; gBias = G[5];
        }

        double step(Row r) {
            System.arraycopy(E, r.a * D, x, 0, D);
            System.arraycopy(E, r.b * D, x, D, D);
            for (int i = 0; i < H; i++) {
                float s = b1[i];
                int base = i * 2 * D;
                for (int j = 0; j < 2 * D; j++) s += W1[base + j] * x[j];
                z[i] = s;
                h[i] = s > 0 ? s : 0;
            }
            for (int i = 0; i < D; i++) {
                float s = b2[i];
                int base = i * H;
                for (int j = 0; j < H; j++) s += W2[base + j] * h[j];
                oo[i] = s;
            }
            float mx = -1e30f;
            for (int v = 0; v < V; v++) {
                float s = bias[v];
                int base = v * D;
                for (int j = 0; j < D; j++) s += E[base + j] * oo[j];
                lg[v] = s;
                if (s > mx) mx = s;
            }
            double zs = 0;
            for (int v = 0; v < V; v++) zs += Math.exp(lg[v] - mx);
            float lz = (float) (Math.log(zs) + mx);
            double loss = 0;
            for (int k = 0; k < r.t.length; k++) loss -= r.q[k] * (lg[r.t[k]] - lz);
            loss *= r.w;
            Arrays.fill(dO, 0f);
            for (int v = 0; v < V; v++) {
                float g = (float) Math.exp(lg[v] - lz);
                g *= r.w;
                gBias[v] += g;
                int base = v * D;
                for (int j = 0; j < D; j++) {
                    gE[base + j] += g * oo[j];
                    dO[j] += g * E[base + j];
                }
            }
            for (int k = 0; k < r.t.length; k++) {
                int v = r.t[k];
                float g = -r.q[k] * r.w;
                gBias[v] += g;
                int base = v * D;
                for (int j = 0; j < D; j++) {
                    gE[base + j] += g * oo[j];
                    dO[j] += g * E[base + j];
                }
            }
            Arrays.fill(dH, 0f);
            for (int i = 0; i < D; i++) {
                gb2[i] += dO[i];
                int base = i * H;
                for (int j = 0; j < H; j++) {
                    gW2[base + j] += dO[i] * h[j];
                    dH[j] += W2[base + j] * dO[i];
                }
            }
            Arrays.fill(dX, 0f);
            for (int i = 0; i < H; i++) {
                if (z[i] <= 0) continue;
                float g = dH[i];
                gb1[i] += g;
                int base = i * 2 * D;
                for (int j = 0; j < 2 * D; j++) {
                    gW1[base + j] += g * x[j];
                    dX[j] += W1[base + j] * g;
                }
            }
            for (int j = 0; j < D; j++) {
                gE[r.a * D + j] += dX[j];
                gE[r.b * D + j] += dX[D + j];
            }
            return loss;
        }
    }
}
