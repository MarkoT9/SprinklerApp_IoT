#include <DHT.h>
#include <ArduinoBLE.h>
#include <WiFiNINA.h>
#include <Firebase_Arduino_WiFiNINA.h>
#include <FlashStorage.h>

// DHT11 senzor teploty a vlhkosti
#define DHTPIN  5
#define DHTTYPE DHT11
DHT dht(DHTPIN, DHTTYPE);

float dhtTemperature = 0.0;
float dhtHumidity    = 0.0;
unsigned long lastDHTRead    = 0;
unsigned long lastNtpAttempt = 0;


// FIREBASE – načítané z flash, nie napevno v kóde
FirebaseData firebaseData;
String firebasePath = "";  // predvolená cesta; prepísaná z flash alebo BLE
String firebaseHost = "";
String firebaseAuth = "";


// WIFI ÚDAJE (uložené vo flash pamäti)
struct WifiCredentials {
  bool valid;
  char ssid[64];
  char pass[64];
  char host[128];
  char auth[64];
  char path[64];  // Firebase cesta, napr. "/sprinkler"
};

FlashStorage(storage, WifiCredentials);

String wifiSSID = "";
String wifiPASS = "";


// PINY
int soilSensor       = A1;
int rainSensor       = A2;
int lightSensor      = 3;
#define RELAY_PIN       2
#define WATER_LEVEL_PIN 4  // INPUT_PULLUP, LOW = nádrž prázdna


// KALIBRÁCIA
const int SOIL_DRY = 675;  // suchá pôda
const int SOIL_WET = 220;  // merané vo vode
const int RAIN_DRY = 675;  // bez dažďa
const int RAIN_WET = 340;  // merané postriekaním


// STAV
int  moisture       = 0;
int  rains          = 0;
bool sprinklerState = false;
bool autoMode       = true;
bool manualWater    = false;
bool waterLow       = false;

bool dhtError  = false;
bool soilError = false;
bool rainError = false;


// NTP synchronizácia času
unsigned long bootUnixTime = 0;
unsigned long bootMillis   = 0;

unsigned long getCurrentUnixTime() {
  if (bootUnixTime == 0) return 0;
  return bootUnixTime + ((millis() - bootMillis) / 1000);
}

void syncNTPTime() {
  Serial.print("Synchronizujem NTP cas");
  unsigned long t = 0;
  for (int i = 0; i < 10 && t == 0; i++) {
    delay(500);
    t = WiFi.getTime();
    Serial.print(".");
  }
  if (t > 0) {
    bootUnixTime = t;
    bootMillis   = millis();
    Serial.print(" OK: ");
    Serial.println(t);
  } else {
    Serial.println(" ZLYHALO");
  }
}


// HISTÓRIA
#define HISTORY_INTERVAL 60  // 60 × 2s = 2 minúty
int historyCounter = 0;

bool ntpSynced = false;


// PRIEMER VLHKOSTI PÔDY (5 meraní)
#define SOIL_SAMPLES 5
int soilSamples[SOIL_SAMPLES];
int soilIndex = 0;
bool soilBufferInitialized = false;

int getAverageSoil(int newValue) {
  if (!soilBufferInitialized) {
    for (int i = 0; i < SOIL_SAMPLES; i++) soilSamples[i] = newValue;
    soilBufferInitialized = true;
  }
  soilSamples[soilIndex] = newValue;
  soilIndex = (soilIndex + 1) % SOIL_SAMPLES;
  int sum = 0;
  for (int i = 0; i < SOIL_SAMPLES; i++) sum += soilSamples[i];
  return sum / SOIL_SAMPLES;
}


// BLE UUID – musia byť rovnaké ako v Android aplikácii
#define SERVICE_UUID     "484bb508-f485-41a1-802c-cb8bb0bdfbf0"
#define CHAR_SSID_UUID   "9312f166-ff7c-42d0-9c89-1c5a2ea0b080"
#define CHAR_PASS_UUID   "b9e17c9f-1474-44a0-a332-f9894d626b08"
#define CHAR_HOST_UUID   "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
#define CHAR_AUTH_UUID   "b2c3d4e5-f6a7-8901-bcde-f12345678901"
#define CHAR_PATH_UUID   "c3d4e5f6-a7b8-9012-cdef-123456789012"

BLEService sprinklerService(SERVICE_UUID);
BLEStringCharacteristic ssidChar  (CHAR_SSID_UUID,   BLEWrite | BLERead,   64);
BLEStringCharacteristic passChar  (CHAR_PASS_UUID,   BLEWrite | BLERead,   64);
BLEStringCharacteristic hostChar  (CHAR_HOST_UUID,   BLEWrite | BLERead,  128);
BLEStringCharacteristic authChar  (CHAR_AUTH_UUID,   BLEWrite | BLERead,   64);
BLEStringCharacteristic pathChar  (CHAR_PATH_UUID,   BLEWrite | BLERead,   64);

String pendingSSID = "";
String pendingPASS = "";
String pendingHOST = "";
String pendingAUTH = "";
String pendingPATH = "";


// WIFI pripojenie
bool connectWiFi() {
  if (wifiSSID.length() == 0) return false;
  Serial.print("Pripajam sa na WiFi: ");
  Serial.println(wifiSSID);
  WiFi.begin(wifiSSID.c_str(), wifiPASS.c_str());
  unsigned long start = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - start < 15000) {
    delay(500);
    Serial.print(".");
  }
  Serial.println();
  if (WiFi.status() == WL_CONNECTED) {
    Serial.print("Pripojene! IP: ");
    Serial.println(WiFi.localIP());
    return true;
  }
  Serial.println("Pripojenie na WiFi zlyhalo.");
  return false;
}


// ČÍTANIE OVLÁDANIA Z FIREBASE
// autoMode a manualWater zapisuje len aplikácia, Arduino ich len číta
void readFirebaseControls() {
  if (Firebase.getBool(firebaseData, firebasePath + "/mode/autoMode"))
    autoMode = firebaseData.boolData();
  if (Firebase.getBool(firebaseData, firebasePath + "/mode/manualWater"))
    manualWater = firebaseData.boolData();
}


// LOGIKA ZAVLAŽOVANIA
void updateWateringLogic() {
  if (autoMode) {
    if (waterLow) {
      sprinklerState = false;
    } else {
      // zapni ak je suchá pôda, neprší a DHT funguje
      sprinklerState = !dhtError ? (moisture < 50 && rains < 50) : false;
    }
  } else {
    // manuálny režim – rozhoduje používateľ cez aplikáciu
    sprinklerState = manualWater;
  }
  digitalWrite(RELAY_PIN, sprinklerState ? HIGH : LOW);
}


// BLE PROVISIONING – prijme WiFi a Firebase údaje z aplikácie
bool runBLEProvisioning() {
  Serial.println("BLE provisioning okno otvorene (60s)...");

  if (!BLE.begin()) {
    Serial.println("Inicializacia BLE zlyhala!");
    return false;
  }

  BLE.setLocalName("SprinklerIoT");
  BLE.setAdvertisedService(sprinklerService);
  sprinklerService.addCharacteristic(ssidChar);
  sprinklerService.addCharacteristic(passChar);
  sprinklerService.addCharacteristic(hostChar);
  sprinklerService.addCharacteristic(authChar);
  sprinklerService.addCharacteristic(pathChar);
  BLE.addService(sprinklerService);
  ssidChar.writeValue("");
  passChar.writeValue("");
  hostChar.writeValue("");
  authChar.writeValue("");
  pathChar.writeValue("");
  BLE.advertise();
  Serial.println("BLE vysielam ako 'SprinklerIoT'...");

  unsigned long windowStart = millis();

  while (millis() - windowStart < 60000) {
    BLEDevice central = BLE.central();

    if (central) {
      Serial.print("Aplikacia pripojena: ");
      Serial.println(central.address());
      digitalWrite(LED_BUILTIN, HIGH);

      while (central.connected()) {
        if (ssidChar.written()) {
          pendingSSID = ssidChar.value();
          Serial.print("SSID prijate: ");
          Serial.println(pendingSSID);
        }

        if (passChar.written()) {
          pendingPASS = passChar.value();
          Serial.println("Heslo prijate.");
        }

        if (hostChar.written()) {
          pendingHOST = hostChar.value();
          Serial.print("Firebase host prijaty: ");
          Serial.println(pendingHOST);
        }

        if (authChar.written()) {
          pendingAUTH = authChar.value();
          Serial.println("Firebase auth prijaty.");
        }

        if (pathChar.written()) {
          pendingPATH = pathChar.value();
          Serial.print("Firebase cesta prijata: ");
          Serial.println(pendingPATH);

          // Cesta je posledná odoslaná hodnota – uložíme všetko a pripojíme sa
          if (pendingSSID.length() > 0 && pendingHOST.length() > 0 && pendingPATH.length() > 0) {
            wifiSSID     = pendingSSID;
            wifiPASS     = pendingPASS;
            firebaseHost = pendingHOST;
            firebaseAuth = pendingAUTH;
            firebasePath = pendingPATH;

            // Uložíme do flash pamäte
            WifiCredentials creds;
            creds.valid = true;
            wifiSSID.toCharArray(creds.ssid, 64);
            wifiPASS.toCharArray(creds.pass, 64);
            firebaseHost.toCharArray(creds.host, 128);
            firebaseAuth.toCharArray(creds.auth, 64);
            firebasePath.toCharArray(creds.path, 64);
            storage.write(creds);

            // NINA-W102 nedokáže súčasne BLE a WiFi – najprv ukončíme BLE
            BLE.end();
            delay(500);     // dáme čipu čas na prepnutie rádiových režimov
            bool ok = connectWiFi();

            if (ok) {
              Firebase.begin(firebaseHost.c_str(), firebaseAuth.c_str(), wifiSSID.c_str(), wifiPASS.c_str());
              Firebase.reconnectWiFi(true);
              digitalWrite(LED_BUILTIN, LOW);
              return true;
            } else {
              // WiFi zlyhalo – údaje sú uložené vo flash, pri ďalšom cykle sa skúsia znova
              Serial.println("WiFi po BLE provisioningu zlyhalo. Skusim ulozene udaje...");
              pendingSSID = "";
              pendingPASS = "";
              pendingHOST = "";
              pendingAUTH = "";
              pendingPATH = "";
              digitalWrite(LED_BUILTIN, LOW);
              return false;  // vrátime sa do setup() slučky, tá skúsi uložené údaje
            }
          }
        }
      }

      digitalWrite(LED_BUILTIN, LOW);
      Serial.println("Aplikacia sa odpojila.");
    }
  }

  // 60s vypršalo, skúsime uložené údaje
  Serial.println("BLE okno vyprsalo. Skusim ulozene udaje...");
  BLE.end();
  delay(500);
  return false;
}


// SETUP
void setup() {
  Serial.begin(9600);

  pinMode(soilSensor,      INPUT);
  pinMode(rainSensor,      INPUT);
  pinMode(lightSensor,     INPUT);
  pinMode(RELAY_PIN,       OUTPUT);
  pinMode(LED_BUILTIN,     OUTPUT);
  pinMode(WATER_LEVEL_PIN, INPUT_PULLUP);

  digitalWrite(RELAY_PIN,   LOW);
  digitalWrite(LED_BUILTIN, LOW);

  // Inicializácia DHT senzora
  dht.begin();

  while (true) {
    bool newCredentials = runBLEProvisioning();
    if (newCredentials) break;

    WifiCredentials creds = storage.read();
    if (creds.valid) {
      wifiSSID     = String(creds.ssid);
      wifiPASS     = String(creds.pass);
      firebaseHost = String(creds.host);
      firebaseAuth = String(creds.auth);
      // Ak je uložená cesta neprázdna, použijeme ju; inak ponecháme predvolenú
      if (strlen(creds.path) > 0) firebasePath = String(creds.path);
      Serial.println("Skusam ulozene udaje pre: " + wifiSSID);
      Serial.println("Firebase cesta: " + firebasePath);
      bool ok = connectWiFi();
      if (ok) {
        Firebase.begin(firebaseHost.c_str(), firebaseAuth.c_str(), wifiSSID.c_str(), wifiPASS.c_str());
        Firebase.reconnectWiFi(true);
        break;
      }
      Serial.println("Ulozene udaje zlyhali. Restartujem BLE...");
    } else {
      Serial.println("Ziadne ulozene udaje. Restartujem BLE...");
    }
  }
}


// LOOP
void loop() {

  // DHT11 – čítaj max. raz za 2s, chybu detekuj cez isnan()
  if (millis() - lastDHTRead >= 2000) {
    lastDHTRead = millis();
    float t = dht.readTemperature();
    float h = dht.readHumidity();
    if (!isnan(t) && !isnan(h)) {
      dhtTemperature = t;
      dhtHumidity    = h;
      dhtError       = false;
    } else {
      dhtError = true;
      Serial.println("Citanie DHT zlyhalo!");
    }
  }

  // Vlhkosť pôdy
  int rawSoil = analogRead(soilSensor);
  int soil    = getAverageSoil(rawSoil);
  soilError = (soil == 0);
  moisture  = map(soil, SOIL_DRY, SOIL_WET, 0, 100);
  moisture  = constrain(moisture, 0, 100);

  // Dážď
  int rain = analogRead(rainSensor);
  rainError = (rain == 0);
  rains = map(rain, RAIN_DRY, RAIN_WET, 0, 100);
  rains = constrain(rains, 0, 100);

  // Hladina vody
  waterLow = (digitalRead(WATER_LEVEL_PIN) == LOW);

  // WiFi watchdog – pokus o opätovné pripojenie ak sa spojenie stratilo
  if (wifiSSID.length() > 0 && WiFi.status() != WL_CONNECTED) {
    Serial.println("WiFi stratene, pripajam sa znova...");
    if (connectWiFi()) {
      ntpSynced = false;
    }
  }

  // Čítanie ovládania z Firebase (ak sme pripojení)
  if (WiFi.status() == WL_CONNECTED) {
    readFirebaseControls();
  }

  // Logika zavlažovania beží VŽDY – aj keď Firebase/WiFi nefunguje
  updateWateringLogic();

  if (WiFi.status() == WL_CONNECTED) {

    // NTP synchronizácia – skúšame každých 10s kým sa nepodarí
    if (!ntpSynced && millis() - lastNtpAttempt > 10000) {
      lastNtpAttempt = millis();
      syncNTPTime();
      if (bootUnixTime > 0) {
        ntpSynced = true;
      }
    }

    // Aktuálne hodnoty senzorov do Firebase
    Firebase.setFloat(firebaseData, firebasePath + "/current/temperature", dhtTemperature);
    Firebase.setFloat(firebaseData, firebasePath + "/current/humidity",    dhtHumidity);
    Firebase.setInt  (firebaseData, firebasePath + "/current/moisture",    moisture);
    Firebase.setInt  (firebaseData, firebasePath + "/current/rain",        rains);
    Firebase.setInt  (firebaseData, firebasePath + "/current/light",       (digitalRead(lightSensor) == 0 ? 1 : 0));
    Firebase.setBool (firebaseData, firebasePath + "/current/sprinkler",   sprinklerState);
    Firebase.setBool (firebaseData, firebasePath + "/current/waterLow",    waterLow);
    Firebase.setInt(firebaseData, firebasePath + "/current/lastSeen", (long)millis());
    Firebase.setInt(firebaseData, firebasePath + "/current/timestamp", (long)getCurrentUnixTime());

    // História – zápis každé 2 minúty
    historyCounter++;
    if (historyCounter >= HISTORY_INTERVAL) {
      historyCounter = 0;

      unsigned long ts = getCurrentUnixTime();
      String historyPath = firebasePath + "/history/" + String(ts);

      Firebase.setFloat(firebaseData, historyPath + "/temperature", dhtTemperature);
      Firebase.setFloat(firebaseData, historyPath + "/humidity",    dhtHumidity);
      Firebase.setInt  (firebaseData, historyPath + "/moisture",    moisture);
      Firebase.setInt  (firebaseData, historyPath + "/rain",        rains);
      Firebase.setInt  (firebaseData, historyPath + "/light",       (digitalRead(lightSensor) == 0 ? 1 : 0));
      Firebase.setBool (firebaseData, historyPath + "/sprinkler",   sprinklerState);
      Firebase.setBool (firebaseData, historyPath + "/waterLow",    waterLow);
      Firebase.setInt  (firebaseData, historyPath + "/timestamp",   (long)ts);

      Serial.print("Historia zapisana. Casova peciatka: ");
      Serial.println(ts);
    }
  }

  // Sériový výpis pre ladenie
  Serial.print("{");
  Serial.print("\"temperature\":"); Serial.print(dhtTemperature);
  Serial.print(",\"humidity\":");   Serial.print(dhtHumidity);
  Serial.print(",\"moisture\":");   Serial.print(moisture);
  Serial.print(",\"rain\":");       Serial.print(rains);
  Serial.print(",\"light\":");      Serial.print((digitalRead(lightSensor) == 0 ? 1 : 0));
  Serial.print(",\"waterLow\":");   Serial.print(waterLow ? "true" : "false");
  Serial.print(",\"sprinkler\":\"");Serial.print(sprinklerState ? "ON" : "OFF");
  Serial.print("\",\"autoMode\":"); Serial.print(autoMode ? "1" : "0");
  Serial.print(",\"ntp\":");        Serial.print(getCurrentUnixTime());
  Serial.println("}");

  delay(2000);
}
