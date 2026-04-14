package com.sprinklerapp;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.button.MaterialButton;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class DashboardActivity extends AppCompatActivity {

    // Firebase prihlasovacie údaje – definované centrálne v FirebaseConfig.java
    private static final String FIREBASE_URL    = FirebaseConfig.fullUrl();
    private static final String FIREBASE_SECRET = FirebaseConfig.SECRET;

    private static final long   POLL_INTERVAL_MS  = 2000;  // aktualizácia každých 2 sekundy
    private static final long   HISTORY_POLL_MS   = 120000; // aktualizácia grafu každé 2 minúty

    // Po stlačení tlačidla ignorujeme Firebase poll po dobu 5 sekúnd.
    // Dôvod: Firebase PUT potrebuje čas kým sa zmena propaguje.
    // Ak by sme ihneď čítali stav, dostaneme starý a UI by „poskočil" späť.
    private static final long   WRITE_COOLDOWN_MS = 5000;
    private long lastWriteTime = 0; // čas posledného zápisu z aplikácie

    // UI komponenty
    private TextView tvTemperature, tvHumidity, tvMoisture, tvRain, tvLight;
    private TextView tvSprinklerState, tvModeLabel, tvOfflineBanner;
    private TextView tvWaterLow; // zobrazenie stavu hladiny vody
    private MaterialButton btnAutoMode, btnManualWater;
    private LinearProgressIndicator progressBar;
    private WebView graphWebView;

    // Stav
    private boolean autoMode    = true;
    private boolean manualWater = false;
    private boolean polling     = false;

    // Detekcia offline – ak sa lastSeen nezmenilo 4× po sebe, Arduino je offline
    private static final int OFFLINE_THRESHOLD = 4;
    private long lastSeenValue     = -1;
    private int  unchangedPollCount = 0;

    // Či sa WebView s grafom už načítal (zabraňuje volaniu fetchHistory pred onPageFinished)
    private boolean graphReady = false;

    private final Handler mainHandler    = new Handler(Looper.getMainLooper());
    private final Runnable pollRunnable  = this::fetchCurrent;
    private final Runnable historyRunnable = this::fetchHistory;

    // Graf – aktuálne zobrazené pole
    private String currentGraphField = "temperature";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        tvTemperature    = findViewById(R.id.tvTemperature);
        tvHumidity       = findViewById(R.id.tvHumidity);
        tvMoisture       = findViewById(R.id.tvMoisture);
        tvRain           = findViewById(R.id.tvRain);
        tvLight          = findViewById(R.id.tvLight);
        tvSprinklerState = findViewById(R.id.tvSprinklerState);
        tvModeLabel      = findViewById(R.id.tvModeLabel);
        tvOfflineBanner  = findViewById(R.id.tvOfflineBanner);
        tvWaterLow       = findViewById(R.id.tvWaterLow);
        btnAutoMode      = findViewById(R.id.btnAutoMode);
        btnManualWater   = findViewById(R.id.btnManualWater);
        progressBar      = findViewById(R.id.progressBar);
        graphWebView     = findViewById(R.id.graphWebView);

        // Zobrazíme loading indikátor kým nepríde prvá odpoveď z Firebase
        progressBar.setVisibility(View.VISIBLE);

        // Povolíme JavaScript v WebView pre Chart.js grafy
        WebSettings ws = graphWebView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);

        btnAutoMode.setOnClickListener(v -> toggleAutoMode());
        btnManualWater.setOnClickListener(v -> toggleManualWater());

        // Tlačidlá na prepínanie zobrazeného grafu
        findViewById(R.id.btnGraphTemp).setOnClickListener(v -> {
            currentGraphField = "temperature";
            mainHandler.removeCallbacks(historyRunnable);
            fetchHistory();
        });
        findViewById(R.id.btnGraphHumidity).setOnClickListener(v -> {
            currentGraphField = "humidity";
            mainHandler.removeCallbacks(historyRunnable);
            fetchHistory();
        });
        findViewById(R.id.btnGraphMoisture).setOnClickListener(v -> {
            currentGraphField = "moisture";
            mainHandler.removeCallbacks(historyRunnable);
            fetchHistory();
        });
        findViewById(R.id.btnGraphRain).setOnClickListener(v -> {
            currentGraphField = "rain";
            mainHandler.removeCallbacks(historyRunnable);
            fetchHistory();
        });

        loadGraphShell();
    }

    @Override
    protected void onResume() {
        super.onResume();
        polling = true;
        fetchCurrent();
        // fetchHistory sa spustí automaticky cez onPageFinished (prvý raz)
        // alebo tu pri opätovnom návrate do aktivity
        if (graphReady) {
            mainHandler.removeCallbacks(historyRunnable);
            fetchHistory();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        polling = false;
        mainHandler.removeCallbacks(pollRunnable);
        mainHandler.removeCallbacks(historyRunnable);
    }

    // Načítame HTML stránku s Chart.js grafom do WebView.
    // fetchHistory() sa volá až po onPageFinished – vtedy je window.updateChart pripravený.
    private void loadGraphShell() {
        graphWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // WebView je pripravený – teraz môžeme hneď vykresľovať graf
                graphReady = true;
                if (polling) fetchHistory();
            }
        });

        String html = "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{margin:0;padding:0;background:#1A1D27;}canvas{display:block;}</style>"
                + "<script src='https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js'></script>"
                + "</head><body>"
                + "<div style='position:relative;width:100%;height:220px;'>"
                + "<canvas id='chart'></canvas></div>"
                + "<script>"
                + "window.myChart = new Chart(document.getElementById('chart'), {"
                + "  type: 'line',"
                + "  data: { labels: [], datasets: [{ data: [], borderColor: '#4ADE80', borderWidth: 2,"
                + "    pointRadius: 3, pointBackgroundColor: '#4ADE80', fill: true,"
                + "    backgroundColor: 'rgba(74,222,128,0.08)', tension: 0.3 }] },"
                + "  options: { responsive: true, maintainAspectRatio: false, animation: false,"
                + "    plugins: { legend: { display: false } },"
                + "    scales: {"
                + "      x: { ticks: { color: '#8A8EA6', font: { size: 10 }, maxRotation: 45, autoSkip: true, maxTicksLimit: 8 },"
                + "           grid: { color: 'rgba(255,255,255,0.05)' } },"
                + "      y: { min: 0, max: 50, ticks: { color: '#8A8EA6', font: { size: 10 } },"
                + "           grid: { color: 'rgba(255,255,255,0.05)' } }"
                + "    }"
                + "  }"
                + "});"
                + "window.updateChart = function(labels, data, label, yMin, yMax) {"
                + "  window.myChart.data.labels = labels;"
                + "  window.myChart.data.datasets[0].data = data;"
                + "  window.myChart.data.datasets[0].label = label;"
                + "  window.myChart.options.scales.y.min = yMin;"
                + "  window.myChart.options.scales.y.max = yMax;"
                + "  window.myChart.update();"
                + "};"
                + "</script></body></html>";
        graphWebView.loadDataWithBaseURL("https://cdn.jsdelivr.net", html, "text/html", "utf-8", null);
    }

    // Stiahne historické záznamy za posledných 24 hodín a vykreslí ich do grafu.
    // Používa orderBy="$key" – kľúče sú Unix timestamp stringy, takže sú prirodzene chronologické.
    // Nevyžaduje Firebase index na "timestamp". limitToLast=720 = posledných 24h pri 2min intervale.
    private void fetchHistory() {
        if (!graphReady) return;

        String path = "/history.json?orderBy=%22%24key%22&limitToLast=720";
        firebaseGet(path, response -> {
            if (response != null && !response.equals("null")) {
                try {
                    JSONObject obj = new JSONObject(response);
                    List<long[]> points = new ArrayList<>();

                    Iterator<String> keys = obj.keys();
                    while (keys.hasNext()) {
                        String key   = keys.next();
                        JSONObject entry = obj.optJSONObject(key);
                        if (entry == null) continue;
                        long ts    = entry.optLong("timestamp", 0);
                        double val = entry.optDouble(currentGraphField, 0);
                        points.add(new long[]{ ts, (long)(val * 10) });
                    }

                    Collections.sort(points, (a, b) -> Long.compare(a[0], b[0]));

                    StringBuilder labelsJson = new StringBuilder("[");
                    StringBuilder dataJson   = new StringBuilder("[");

                    SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.US);
                    sdf.setTimeZone(TimeZone.getDefault());

                    for (int i = 0; i < points.size(); i++) {
                        long ts  = points.get(i)[0];
                        float val = points.get(i)[1] / 10f;

                        String label;
                        // Ak máme NTP timestamp (> rok 2001), zobrazíme čas
                        // Inak zobrazíme minúty od štartu
                        if (ts > 1000000000L) {
                            label = sdf.format(new Date(ts * 1000L));
                        } else {
                            label = (ts / 60) + "m";
                        }

                        labelsJson.append("\"").append(label).append("\"");
                        dataJson.append(val);
                        if (i < points.size() - 1) { labelsJson.append(","); dataJson.append(","); }
                    }
                    labelsJson.append("]");
                    dataJson.append("]");

                    String fieldLabel = currentGraphField.substring(0, 1).toUpperCase()
                            + currentGraphField.substring(1);
                    int yMin, yMax;
                    switch (currentGraphField) {
                        case "temperature": yMin = 0;  yMax = 50;  break;
                        case "humidity":    yMin = 0;  yMax = 100; break;
                        case "moisture":    yMin = 0;  yMax = 100; break;
                        case "rain":        yMin = 0;  yMax = 100; break;
                        default:            yMin = 0;  yMax = 100; break;
                    }
                    String js = "updateChart(" + labelsJson + "," + dataJson
                            + ",'" + fieldLabel + "'," + yMin + "," + yMax + ");";
                    graphWebView.post(() -> graphWebView.evaluateJavascript(js, null));

                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
            if (polling) mainHandler.postDelayed(historyRunnable, HISTORY_POLL_MS);
        });
    }

    // Stiahne aktuálne hodnoty senzorov a stav režimu
    private void fetchCurrent() {
        firebaseGet("/current.json", response -> {
            if (response != null && !response.equals("null")) {
                try {
                    JSONObject obj = new JSONObject(response);
                    float temp    = (float) obj.optDouble("temperature", 0);
                    float hum     = (float) obj.optDouble("humidity", 0);
                    int moist     = obj.optInt("moisture", 0);
                    int rain      = obj.optInt("rain", 0);
                    int light     = obj.optInt("light", 0);
                    boolean spr   = obj.optBoolean("sprinkler", false);
                    boolean wLow  = obj.optBoolean("waterLow", false);
                    long lastSeen = obj.optLong("lastSeen", 0);  // millis – len na detekciu offline

                    tvTemperature.setText(String.format(Locale.US, "%.1f °C", temp));
                    tvHumidity.setText(String.format(Locale.US, "%.1f %%", hum));
                    tvMoisture.setText(moist + " %");
                    tvRain.setText(rain + " %");
                    tvLight.setText(light == 1 ? "Áno" : "Nie");
                    tvSprinklerState.setText(spr ? "ZAP" : "VYP");
                    tvSprinklerState.setTextColor(getColor(spr ? R.color.accent_green : R.color.error_red));

                    // Skryjeme loading indikátor po prvom úspešnom načítaní
                    progressBar.setVisibility(View.GONE);

                    // Zobrazíme stav hladiny vody z plovákovéha senzora
                    if (tvWaterLow != null) {
                        tvWaterLow.setText(wLow ? "Prázdna" : "OK");
                        tvWaterLow.setTextColor(getColor(wLow ? R.color.error_red : R.color.accent_green));
                    }

                    // Detekcia offline: ak sa lastSeen nezmenilo 4× po sebe, Arduino je offline
                    if (lastSeenValue == -1) {
                        lastSeenValue = lastSeen;
                    } else if (lastSeen == lastSeenValue) {
                        unchangedPollCount++;
                        if (unchangedPollCount >= OFFLINE_THRESHOLD) setOffline(true);
                    } else {
                        lastSeenValue      = lastSeen;
                        unchangedPollCount = 0;
                        setOffline(false);
                    }

                } catch (JSONException e) {
                    e.printStackTrace();
                }
            } else {
                // Prvé načítanie zlyhalo alebo je prázdne – skryjeme loading
                progressBar.setVisibility(View.GONE);
                unchangedPollCount++;
                if (unchangedPollCount >= OFFLINE_THRESHOLD) setOffline(true);
            }
        });

        // Stav režimu čítame len ak NEPREBEHOL zápis v posledných 5 sekundách.
        // Dôvod: Po stlačení tlačidla posielame PUT do Firebase.
        // Firebase potrebuje chvíľu kým sa zmena propaguje.
        // Keby sme ihneď prečítali stav, dostali by sme starý a UI by
        // „poskočil" späť na pôvodnú hodnotu – vyzeralo by že tlačidlo nefunguje.
        boolean writeCooldownActive = (System.currentTimeMillis() - lastWriteTime) < WRITE_COOLDOWN_MS;
        if (!writeCooldownActive) {
            firebaseGet("/mode.json", response -> {
                if (response != null && !response.equals("null")) {
                    try {
                        JSONObject obj = new JSONObject(response);
                        autoMode    = obj.optBoolean("autoMode", true);
                        manualWater = obj.optBoolean("manualWater", false);
                        updateModeUI();
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
            });
        }

        if (polling) mainHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
    }

    // Zobrazenie / skrytie offline bannera
    private void setOffline(boolean offline) {
        tvOfflineBanner.setVisibility(offline ? View.VISIBLE : View.GONE);
    }

    // Prepnutie Auto/Manuálny režim
    private void toggleAutoMode() {
        boolean newVal = !autoMode;
        // Zaznamenáme čas zápisu – cooldown zabráni okamžitému čítaniu zo Firebase
        lastWriteTime = System.currentTimeMillis();

        firebasePut("/mode/autoMode.json", newVal ? "true" : "false", success -> {
            if (success) {
                autoMode = newVal;
                if (autoMode) {
                    // Pri prechode do auto režimu vypneme manuálne zavlažovanie
                    firebasePut("/mode/manualWater.json", "false", ok -> {
                        if (ok) manualWater = false;
                        updateModeUI();
                    });
                } else {
                    updateModeUI();
                }
            } else {
                Toast.makeText(this, "Aktualizácia zlyhala. Skontrolujte pripojenie.", Toast.LENGTH_SHORT).show();
                lastWriteTime = 0; // Neúspešný zápis – zrušíme cooldown
            }
        });
    }

    // Prepnutie manuálneho zapnutia/vypnutia zavlažovacieho ventilu
    private void toggleManualWater() {
        if (autoMode) {
            Toast.makeText(this, "Najprv prepnite do manuálneho režimu", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean newVal = !manualWater;
        // Zaznamenáme čas zápisu – cooldown zabráni okamžitému čítaniu zo Firebase
        lastWriteTime = System.currentTimeMillis();

        firebasePut("/mode/manualWater.json", newVal ? "true" : "false", success -> {
            if (success) {
                manualWater = newVal;
                updateModeUI();
            } else {
                Toast.makeText(this, "Aktualizácia zlyhala. Skontrolujte pripojenie.", Toast.LENGTH_SHORT).show();
                lastWriteTime = 0; // Neúspešný zápis – zrušíme cooldown
            }
        });
    }

    // Aktualizácia vzhľadu tlačidiel podľa aktuálneho stavu
    private void updateModeUI() {
        btnAutoMode.setText(autoMode ? "Auto režim: ZAP" : "Auto režim: VYP");
        btnAutoMode.setStrokeColorResource(autoMode ? R.color.accent_green : R.color.text_secondary);
        btnManualWater.setEnabled(!autoMode);
        btnManualWater.setAlpha(autoMode ? 0.4f : 1.0f);
        btnManualWater.setText(manualWater ? "Manuálne zalievanie: ZAP" : "Manuálne zalievanie: VYP");
        btnManualWater.setStrokeColorResource(!autoMode && manualWater ? R.color.accent_green : R.color.text_secondary);
        tvModeLabel.setText(autoMode ? "Režim: Automatický" : "Režim: Manuálny");
        tvModeLabel.setTextColor(getColor(autoMode ? R.color.accent_green : R.color.text_secondary));
    }

    // Firebase GET – stiahne dáta z danej cesty
    private void firebaseGet(String path, Callback<String> callback) {
        new Thread(() -> {
            try {
                String urlStr = FIREBASE_URL + path
                        + (path.contains("?") ? "&" : "?")
                        + (FIREBASE_SECRET.isEmpty() ? "" : "auth=" + FIREBASE_SECRET);
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close();
                conn.disconnect();
                mainHandler.post(() -> callback.call(sb.toString().trim()));
            } catch (Exception e) {
                mainHandler.post(() -> callback.call(null));
            }
        }).start();
    }

    // Firebase PUT – zapíše hodnotu na danú cestu
    private void firebasePut(String path, String jsonValue, Callback<Boolean> callback) {
        new Thread(() -> {
            try {
                String urlStr = FIREBASE_URL + path
                        + (FIREBASE_SECRET.isEmpty() ? "" : "?auth=" + FIREBASE_SECRET);
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setRequestMethod("PUT");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                OutputStream os = conn.getOutputStream();
                os.write(jsonValue.getBytes());
                os.close();
                int code = conn.getResponseCode();
                conn.disconnect();
                mainHandler.post(() -> callback.call(code == 200));
            } catch (Exception e) {
                mainHandler.post(() -> callback.call(false));
            }
        }).start();
    }

    interface Callback<T> { void call(T value); }
}
