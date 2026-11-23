package de.andre.solarguardianlocal;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.*;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.util.*;

/** Local-only BLE/Modbus reader. It makes no network requests. */
public class MainActivity extends Activity {
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ArrayList<Device> found = new ArrayList<>();
  private final ArrayList<Model> models = new ArrayList<>();
  private final ArrayDeque<Point> pendingReads = new ArrayDeque<>();
  private final ArrayList<Point> livePoints = new ArrayList<>();
  private final ArrayDeque<String> logLines = new ArrayDeque<>();
  private BluetoothAdapter adapter;
  private BluetoothLeScanner scanner;
  private BluetoothGatt gatt;
  private BluetoothGattCharacteristic tx, rx;
  private ScanCallback scanCallback;
  private TextView status, log;
  private Button liveButton;
  private Spinner modelPicker;
  private LinearLayout deviceList, valuesList;
  private View mainView;
  private LinearLayout settingsPage;
  private TextView scanHint;
  private Model selectedModel;
  private Point currentPoint;
  private final HashMap<Point,EditText> settingInputs=new HashMap<>();
  private final ArrayDeque<SettingWrite> settingWrites=new ArrayDeque<>();
  private int settingsWriteTotal,settingsWriteDone;
  private Runnable requestTimeout;
  private Runnable writeTimeout;
  private byte[] pendingWrite;
  private String pendingWriteName;
  private final ByteArrayOutputStream response = new ByteArrayOutputStream();
  private boolean busy;
  private boolean pollingLive;
  private final Runnable livePoll = () -> { if (pollingLive) readLive(); };
  private static final int PERMISSION_REQUEST = 17;
  private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
  private static final int NAVY=Color.rgb(20,48,72), TEAL=Color.rgb(0,132,121), BLUE=Color.rgb(40,105,173), BG=Color.rgb(241,246,249), INK=Color.rgb(33,49,63), MUTED=Color.rgb(100,116,129);

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    BluetoothManager manager=(BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
    adapter=manager==null?null:manager.getAdapter();
    copyDatabase();
    loadModels();
    showSplash();
  }

  private void showSplash(){
    LinearLayout splash=new LinearLayout(this);splash.setGravity(android.view.Gravity.CENTER);splash.setOrientation(LinearLayout.VERTICAL);splash.setPadding(dp(24),dp(24),dp(24),dp(24));splash.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{NAVY,Color.rgb(0,105,112)}));
    TextView sun=text("☀",64);sun.setGravity(android.view.Gravity.CENTER);sun.setTextColor(Color.rgb(255,204,80));splash.addView(sun);
    ValueAnimator anim=ValueAnimator.ofFloat(0,360);anim.setDuration(2200);anim.setRepeatCount(ValueAnimator.INFINITE);anim.addUpdateListener(a->sun.setRotation((float)a.getAnimatedValue()));anim.start();
    TextView title=text("Solar Guardian Local",25);title.setGravity(android.view.Gravity.CENTER);title.setTextColor(Color.WHITE);splash.addView(title);
    TextView by=text("programmiert von Greatnet 89",15);by.setGravity(android.view.Gravity.CENTER);by.setTextColor(Color.rgb(218,234,240));splash.addView(by);
    setContentView(splash);handler.postDelayed(()->{anim.cancel();buildUi();requestBluetoothPermission();},5000);
  }

  private void buildUi() {
    ScrollView scroll=new ScrollView(this);
    LinearLayout page=new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(16),dp(14),dp(16),dp(20)); page.setBackgroundColor(BG);
    LinearLayout hero=new LinearLayout(this); hero.setOrientation(LinearLayout.VERTICAL); hero.setPadding(dp(18),dp(18),dp(18),dp(18)); hero.setBackground(shape(NAVY,18));
    TextView title=text("Solar Guardian Local",25); title.setTextColor(Color.WHITE); hero.addView(title);
    TextView subtitle=text("BLE-Verbindung direkt zum Regler",14); subtitle.setTextColor(Color.rgb(208,225,237)); hero.addView(subtitle); page.addView(hero);
    LinearLayout statusCard=card(); status=text("Bereit – zuerst Modell und danach BLE-Stick auswählen",15); status.setTextColor(INK); statusCard.addView(status); page.addView(statusCard);

    LinearLayout modelCard=card(); sectionTitle(modelCard,"1  Regler auswählen","Das ist dein EPEVER-Reglermodell, nicht der BLE-Stick.");
    modelPicker=new Spinner(this);
    ArrayList<String> names=new ArrayList<>(); for(Model m:models) names.add(m.name);
    modelPicker.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));
    modelPicker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
      public void onNothingSelected(android.widget.AdapterView<?> p){}
      public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){ if(pos>=0&&pos<models.size()){selectedModel=models.get(pos); loadPoints();}}
    });
    modelCard.addView(modelPicker); page.addView(modelCard);

    LinearLayout bleCard=card(); sectionTitle(bleCard,"2  BLE-Stick suchen","Tippe auf „Suchen“. Geräte erscheinen direkt darunter; wähle dann den EPEVER-Stick aus.");
    LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
    Button scanButton=button("BLE-Geräte suchen",()->startScan()); scanButton.setBackgroundTintList(ColorStateList.valueOf(TEAL)); row.addView(scanButton,new LinearLayout.LayoutParams(0,dp(50),1));
    Button disconnectButton=button("Trennen",()->disconnect()); disconnectButton.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(102,119,133))); LinearLayout.LayoutParams disconnectParams=new LinearLayout.LayoutParams(0,dp(50),1); disconnectParams.leftMargin=dp(8); row.addView(disconnectButton,disconnectParams); bleCard.addView(row);
    scanHint=text("Noch keine Geräte gefunden.",14); scanHint.setTextColor(MUTED); bleCard.addView(scanHint);
    deviceList=new LinearLayout(this); deviceList.setOrientation(LinearLayout.VERTICAL); bleCard.addView(deviceList);
    Button connectButton=button("Ausgewähltes Gerät verbinden",()->connectSelected()); connectButton.setBackgroundTintList(ColorStateList.valueOf(BLUE)); bleCard.addView(connectButton); page.addView(bleCard);

    LinearLayout liveCard=card(); sectionTitle(liveCard,"3  Live-Daten","Werte werden direkt vom Regler gelesen und alle 10 Sekunden aktualisiert.");
    liveButton=button("Live-Daten starten",()->toggleLive()); liveButton.setBackgroundTintList(ColorStateList.valueOf(TEAL)); liveCard.addView(liveButton);
    valuesList=new LinearLayout(this); valuesList.setOrientation(LinearLayout.VERTICAL); liveCard.addView(valuesList); page.addView(liveCard);

    LinearLayout settingsCard=card(); sectionTitle(settingsCard,"4  Einstellungen","Modellabhängige Register am Regler anzeigen oder ändern.");
    Button settingsButton=button("Einstellungen anzeigen / ändern",()->showSettings()); settingsButton.setBackgroundTintList(ColorStateList.valueOf(BLUE)); settingsCard.addView(settingsButton); page.addView(settingsCard);

    LinearLayout logCard=card(); sectionTitle(logCard,"Verbindungsprotokoll","Status und Scan-Meldungen"); log=text("Noch keine Verbindung.",12); log.setTextColor(MUTED); logCard.addView(log); page.addView(logCard);
    scroll.addView(page); mainView=scroll; setContentView(scroll);
  }
  private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
  private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
  private LinearLayout card(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(14),dp(12),dp(14),dp(14));c.setBackground(shape(Color.WHITE,16));c.setElevation(dp(2));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(10);lp.bottomMargin=dp(2);c.setLayoutParams(lp);return c;}
  private void sectionTitle(LinearLayout parent,String title,String description){TextView h=text(title,18);h.setTextColor(NAVY);h.setTypeface(null,android.graphics.Typeface.BOLD);parent.addView(h);TextView d=text(description,13);d.setTextColor(MUTED);parent.addView(d);}
  private TextView text(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(INK);t.setPadding(0,dp(5),0,dp(7));return t;}
  private Button button(String s,Runnable action){Button b=new Button(this);b.setText(s);b.setTextColor(NAVY);b.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(230,238,243)));b.setOnClickListener(v->action.run());return b;}
  private void say(String s){runOnUiThread(()->{if(status!=null)status.setText(s);addLog(s);});}
  private void addLog(String s){if(log==null)return;logLines.addLast(s);while(logLines.size()>5)logLines.removeFirst();StringBuilder b=new StringBuilder();for(String line:logLines){if(b.length()>0)b.append('\n');b.append(line);}log.setText(b.toString());}

  private void copyDatabase(){
    File f=new File(getFilesDir(),"epever.db"); if(f.exists())return;
    try(InputStream in=getAssets().open("epever.db");OutputStream out=new FileOutputStream(f)){byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);}
    catch(Exception e){say("Offline-Datenbank konnte nicht geladen werden: "+e.getMessage());}
  }
  private android.database.sqlite.SQLiteDatabase db(){return android.database.sqlite.SQLiteDatabase.openDatabase(new File(getFilesDir(),"epever.db").getAbsolutePath(),null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY);}
  private void loadModels(){
    try(android.database.sqlite.SQLiteDatabase d=db();android.database.Cursor c=d.rawQuery("SELECT id, productNameE FROM hn_product_basic_info WHERE type=199 ORDER BY productNameE",null)){
      while(c.moveToNext())models.add(new Model(c.getInt(0),c.getString(1)));
    }catch(Exception e){models.add(new Model(0,"EPEVER controller (manual)"));}
  }
  private void loadPoints(){
    if(selectedModel==null||selectedModel.id==0)return;
    valuesList.removeAllViews();livePoints.clear();
    try(android.database.sqlite.SQLiteDatabase d=db();android.database.Cursor c=d.rawQuery("SELECT variableNameE,dataIdentifier,register,address,dataFormat,decimal,unit,acquisitionFormula FROM hn_product_variable WHERE productId=? AND type=0 ORDER BY groupId,sort",new String[]{String.valueOf(selectedModel.id)})){
      while(c.moveToNext()){
        String name=c.getString(0), key=c.getString(1), reg=c.getString(2), addr=c.getString(3), fmt=c.getString(4), dec=c.getString(5), unit=c.getString(6), formula=c.getString(7);
        if(name==null||key==null||addr==null||fmt==null)continue;
        if(!isLiveKey(key))continue;
        Point p=new Point(name,key,readFunction(parse(reg,3)),parse(addr,0),fmt,parse(dec,0),unit==null?"":unit,"",formula==null?"":formula);
        livePoints.add(p);
        LinearLayout group=groupFor(key);
        if(group==null)group=newGaugeGroup(gaugeGroupName(key));
        LinearLayout row=(LinearLayout)group.getChildAt(group.getChildCount()-1);if(row.getChildCount()>=2){row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);group.addView(row);}
        if(isEnergyKey(key)){TextView tile=text(name+"\n—",15);tile.setGravity(android.view.Gravity.CENTER);tile.setTextColor(NAVY);tile.setTypeface(null,android.graphics.Typeface.BOLD);tile.setBackground(shape(Color.rgb(232,243,244),12));tile.setTag(p);row.addView(tile,new LinearLayout.LayoutParams(0,dp(78),1));}
        else{GaugeView gauge=new GaugeView(this,p);gauge.setTag(p);row.addView(gauge,new LinearLayout.LayoutParams(0,dp(155),1));}
      }
      if(livePoints.isEmpty())valuesList.addView(text("Für dieses Modell sind keine Live-Felder in der Offline-Datenbank markiert.",14));
    }catch(Exception e){valuesList.addView(text("Modell-Daten konnten nicht gelesen werden: "+e.getMessage(),14));}
  }
  private String gaugeGroupName(String key){String k=key.toLowerCase(Locale.ROOT);if(k.startsWith("pv_"))return "PV";if(k.startsWith("bat_")||k.contains("battery"))return "Batterie";if(k.startsWith("load_"))return "Verbraucher";if(k.contains("devicetem"))return "Regler";return "Ertrag und Verbrauch";}
  private boolean isEnergyKey(String key){String k=key.toLowerCase(Locale.ROOT);return k.contains("powergeneration")||k.contains("powerconsumption");}
  private LinearLayout groupFor(String key){String title=gaugeGroupName(key);for(int i=0;i<valuesList.getChildCount();i++){View v=valuesList.getChildAt(i);if(title.equals(v.getTag()))return (LinearLayout)v;}return null;}
  private LinearLayout newGaugeGroup(String title){LinearLayout card=card();card.setTag(title);TextView h=text(title,18);h.setTextColor(NAVY);h.setTypeface(null,android.graphics.Typeface.BOLD);card.addView(h);LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);card.addView(row);valuesList.addView(card);return card;}
  private boolean isLiveKey(String key){String k=key.toLowerCase(Locale.ROOT);return k.startsWith("pv_")||k.startsWith("bat_")||k.startsWith("load_")||k.contains("powergeneration")||k.contains("powerconsumption")||k.contains("devicetem");}
  private int parse(String s,int fallback){try{return Integer.parseInt(s);}catch(Exception e){return fallback;}}
  // The vendor database uses 3=input register (Modbus FC4) and 4=output register (FC3).
  private int readFunction(int register){if(register==3)return 4;if(register==4)return 3;if(register==1)return 2;if(register==0)return 1;return register;}

  private String[] permissions(){return Build.VERSION.SDK_INT>=31?new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT}:new String[]{Manifest.permission.ACCESS_FINE_LOCATION};}
  private void requestBluetoothPermission(){ArrayList<String> missing=new ArrayList<>();for(String p:permissions())if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED)missing.add(p);if(!missing.isEmpty())requestPermissions(missing.toArray(new String[0]),PERMISSION_REQUEST);}
  private boolean permitted(){for(String p:permissions())if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED)return false;return true;}
  @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==PERMISSION_REQUEST){boolean ok=g.length>0;for(int x:g)ok&=x==PackageManager.PERMISSION_GRANTED;say(ok?"Bluetooth-Berechtigung erteilt.":"Bitte Bluetooth-Berechtigung erlauben.");}}

  private void startScan(){
    if(!permitted()){say("Für den Scan bitte „Geräte in der Nähe“ erlauben.");requestBluetoothPermission();return;}
    if(adapter==null){say("Dieses Handy stellt keinen Bluetooth-Adapter bereit.");return;}
    if(!adapter.isEnabled()){say("Bitte Bluetooth in den Schnelleinstellungen einschalten.");return;}
    scanner=adapter.getBluetoothLeScanner(); if(scanner==null){say("Android hat keinen BLE-Scanner bereitgestellt. Bluetooth aus- und wieder einschalten.");return;}
    stopScan();found.clear();deviceList.removeAllViews();scanHint.setVisibility(View.VISIBLE);scanHint.setText("Suche läuft – halte diese App geöffnet.");say("Suche 12 Sekunden nach BLE-Geräten …");
    scanCallback=new ScanCallback(){
      @Override public void onScanResult(int type,ScanResult result){addScanResult(result);}
      @Override public void onBatchScanResults(List<ScanResult> results){for(ScanResult result:results)addScanResult(result);}
      @Override public void onScanFailed(int errorCode){runOnUiThread(()->{stopScan();String message="BLE-Scan fehlgeschlagen (Code "+errorCode+"): "+scanError(errorCode);scanHint.setText(message);scanHint.setVisibility(View.VISIBLE);say(message);});}
    };
    final ScanCallback active=scanCallback;
    try{
      ScanSettings settings=new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
      scanner.startScan(null,settings,active);
      handler.postDelayed(()->{if(scanCallback==active){stopScan();String message="Suche beendet: "+found.size()+" Gerät(e) gefunden.";if(found.isEmpty())message+=" Prüfe, ob Bluetooth an ist und der Stick Strom hat.";scanHint.setText(message);scanHint.setVisibility(View.VISIBLE);say(message);}},12000);
    }catch(SecurityException e){stopScan();String message="Scanberechtigung fehlt. Öffne App-Info → Berechtigungen → Geräte in der Nähe und erlaube sie.";scanHint.setText(message);say(message);}
    catch(Exception e){stopScan();String message="Scan konnte nicht starten: "+e.getClass().getSimpleName()+" – "+e.getMessage();scanHint.setText(message);say(message);}
  }
  private String scanError(int code){switch(code){case ScanCallback.SCAN_FAILED_ALREADY_STARTED:return "Der Scan läuft bereits.";case ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:return "Android konnte die App nicht beim Bluetooth-Dienst anmelden. Bluetooth neu starten und erneut versuchen.";case ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED:return "BLE-Scan wird von diesem Gerät nicht unterstützt.";case ScanCallback.SCAN_FAILED_INTERNAL_ERROR:return "Interner Bluetooth-Fehler. Bluetooth neu starten.";case ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES:return "Zu viele gleichzeitige Bluetooth-Scans.";case ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY:return "Android hat zu häufige Scans gebremst. Bitte kurz warten.";default:return "Bitte Bluetooth und Berechtigung prüfen.";}}
  private void addScanResult(ScanResult result){runOnUiThread(()->{BluetoothDevice d=result.getDevice();String address;try{address=d.getAddress();}catch(SecurityException e){return;}for(Device old:found)if(old.address.equals(address)){old.rssi=result.getRssi();return;}String n="BLE-Gerät";try{if(d.getName()!=null&&!d.getName().trim().isEmpty())n=d.getName();}catch(SecurityException ignored){}Device item=new Device(d,n,result.getRssi());found.add(item);scanHint.setVisibility(View.GONE);Button b=button(n+"\n"+address+" · "+result.getRssi()+" dBm",()->{selectedDevice=item;say("Ausgewählt: "+item.name);});b.setGravity(android.view.Gravity.START|android.view.Gravity.CENTER_VERTICAL);b.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(230,244,241)));deviceList.addView(b);});}
  private Device selectedDevice;
  private void stopScan(){if(scanner!=null&&scanCallback!=null)try{scanner.stopScan(scanCallback);}catch(Exception ignored){}scanCallback=null;}
  private void connectSelected(){if(!permitted()){requestBluetoothPermission();return;}if(selectedDevice==null){say("Bitte zuerst den BLE-Stick aus der Liste auswählen.");return;}disconnect();say("Verbinde mit "+selectedDevice.name+" …");gatt=selectedDevice.device.connectGatt(this,false,gattCallback,BluetoothDevice.TRANSPORT_LE);}
  private void disconnect(){stopScan();pollingLive=false;handler.removeCallbacks(livePoll);if(requestTimeout!=null)handler.removeCallbacks(requestTimeout);requestTimeout=null;pendingReads.clear();currentPoint=null;if(liveButton!=null)liveButton.setText("Live-Daten starten");if(gatt!=null){try{gatt.disconnect();gatt.close();}catch(Exception ignored){}gatt=null;}tx=null;rx=null;busy=false;}

  private final BluetoothGattCallback gattCallback=new BluetoothGattCallback(){
    @Override public void onConnectionStateChange(BluetoothGatt g,int statusCode,int newState){if(newState==BluetoothProfile.STATE_CONNECTED){say("Verbunden – suche GATT-Dienste …");try{g.discoverServices();}catch(SecurityException e){say("Keine Berechtigung für GATT.");}}else if(newState==BluetoothProfile.STATE_DISCONNECTED){pollingLive=false;handler.removeCallbacks(livePoll);runOnUiThread(()->{if(liveButton!=null)liveButton.setText("Live-Daten starten");});say("Verbindung getrennt.");tx=null;rx=null;}}
    @Override public void onServicesDiscovered(BluetoothGatt g,int code){if(code!=BluetoothGatt.GATT_SUCCESS){say("GATT-Ermittlung fehlgeschlagen: "+code);return;}BluetoothGattCharacteristic writeFallback=null,notifyFallback=null;for(BluetoothGattService s:g.getServices())for(BluetoothGattCharacteristic c:s.getCharacteristics()){String id=c.getUuid().toString().toLowerCase(Locale.ROOT);int p=c.getProperties();if(id.startsWith("00002b11-")&&(p&(BluetoothGattCharacteristic.PROPERTY_WRITE|BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE))!=0)tx=c;if(id.startsWith("00002b10-")&&(p&(BluetoothGattCharacteristic.PROPERTY_NOTIFY|BluetoothGattCharacteristic.PROPERTY_INDICATE))!=0)rx=c;if(writeFallback==null&&(p&(BluetoothGattCharacteristic.PROPERTY_WRITE|BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE))!=0)writeFallback=c;if(notifyFallback==null&&(p&(BluetoothGattCharacteristic.PROPERTY_NOTIFY|BluetoothGattCharacteristic.PROPERTY_INDICATE))!=0)notifyFallback=c;}if(tx==null)tx=writeFallback;if(rx==null)rx=notifyFallback;if(tx==null||rx==null){say("Verbindung da, aber TX/RX-Kanal nicht eindeutig gefunden.");return;}try{g.setCharacteristicNotification(rx,true);BluetoothGattDescriptor d=rx.getDescriptor(CCCD);if(d==null){say("RX-Kanal hat keinen Notify-Descriptor.");return;}byte[] value=(rx.getProperties()&BluetoothGattCharacteristic.PROPERTY_INDICATE)!=0?BluetoothGattDescriptor.ENABLE_INDICATION_VALUE:BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;if(Build.VERSION.SDK_INT>=33)g.writeDescriptor(d,value);else{d.setValue(value);g.writeDescriptor(d);}say("Verbunden; RX "+rx.getUuid()+" und TX "+tx.getUuid()+" aktiviert.");}catch(Exception e){say("RX-Aktivierung fehlgeschlagen: "+e.getMessage());}}
    @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int statusCode){say("RX-Descriptor Status "+statusCode+" (0 ist erfolgreich). Bereit für lokale Modbus-Leseabfragen.");}
    @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value){onBleData(value);}
    @Deprecated @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c){onBleData(c.getValue());}
    @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int statusCode){if(statusCode!=BluetoothGatt.GATT_SUCCESS)say("BLE-Schreibstatus "+statusCode);}
  };
  private void onBleData(byte[] data){synchronized(response){response.write(data,0,data.length);}if(currentPoint!=null||pendingWrite!=null)handler.post(this::consumeResponse);}

  private void toggleLive(){if(pollingLive){pollingLive=false;handler.removeCallbacks(livePoll);liveButton.setText("Live-Daten starten");say("Live-Abfrage angehalten.");return;}if(gatt==null||tx==null||rx==null){say("Zuerst mit dem BLE-Stick verbinden.");return;}pollingLive=true;liveButton.setText("Live-Daten stoppen");readLive();}
  private void readLive(){if(gatt==null||tx==null||rx==null){say("Zuerst mit dem BLE-Stick verbinden.");return;}if(busy)return;pendingReads.clear();pendingReads.addAll(livePoints);if(pendingReads.isEmpty()){say("Keine lesbaren Live-Felder für dieses Modell.");return;}say("Lese lokale Reglerwerte per Modbus …");busy=true;nextRead();}
  private void nextRead(){if(pendingReads.isEmpty()){currentPoint=null;busy=false;say("Lesezyklus beendet.");if(pollingLive)handler.postDelayed(livePoll,10000);return;}currentPoint=pendingReads.removeFirst();final Point expectedPoint=currentPoint;response.reset();byte[] req=readFrame(1,currentPoint.function,currentPoint.address-1,registerCount(currentPoint.format));try{write(req);requestTimeout=()->{if(currentPoint==expectedPoint){appendValue(expectedPoint,null,"Keine Antwort / Zeitüberschreitung");currentPoint=null;nextRead();}};handler.postDelayed(requestTimeout,1800);}catch(Exception e){appendValue(currentPoint,null,"Senden fehlgeschlagen");currentPoint=null;nextRead();}}
  private synchronized void write(byte[] bytes){if(gatt==null||tx==null)throw new IllegalStateException("Nicht verbunden");if(Build.VERSION.SDK_INT>=33){int mode=(tx.getProperties()&BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)!=0?BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE:BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;gatt.writeCharacteristic(tx,bytes,mode);}else{tx.setWriteType((tx.getProperties()&BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)!=0?BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE:BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);tx.setValue(bytes);gatt.writeCharacteristic(tx);}}
  private void consumeResponse(){byte[] b; synchronized(response){b=response.toByteArray();}if(b.length<5)return;int fc=b[1]&255;int expected=(fc==5||fc==6||fc==15||fc==16)?8:(fc>=128?5:(b[2]&255)+5);if(b.length<expected)return;
    if(pendingWrite!=null){byte[] sent=pendingWrite;String name=pendingWriteName;pendingWrite=null;pendingWriteName=null;if(writeTimeout!=null)handler.removeCallbacks(writeTimeout);writeTimeout=null;boolean ok=validCrc(b,expected)&&b[1]==sent[1]&&b[2]==sent[2]&&b[3]==sent[3]&&b[4]==sent[4]&&b[5]==sent[5];if(ok){settingsWriteDone++;say("Einstellung bestätigt: "+name);handler.postDelayed(this::writeNextSetting,250);}else{settingWrites.clear();say("Keine gültige Schreibbestätigung für "+name+". Weitere Werte wurden gestoppt.");}return;}
    if(currentPoint==null)return;Point done=currentPoint;if(requestTimeout!=null)handler.removeCallbacks(requestTimeout);requestTimeout=null;try{if(!validCrc(b,expected))throw new Exception("CRC ungültig");int[] regs=new int[(b[2]&255)/2];for(int i=0;i<regs.length;i++)regs[i]=((b[3+i*2]&255)<<8)|(b[4+i*2]&255);double raw=decode(regs,done.format);double value=done.acquisitionFormula.isEmpty()?raw:applyFormula(done.acquisitionFormula,raw);appendValue(done,value,null);}catch(Exception e){appendValue(done,null,"Antwort nicht lesbar: "+e.getMessage());}currentPoint=null;if(busy)handler.postDelayed(this::nextRead,130);}
  private void appendValue(Point p,Double value,String error){runOnUiThread(()->{if(settingsPage!=null){for(int i=0;i<settingsPage.getChildCount();i++){View card=settingsPage.getChildAt(i);if(card.getTag()==p&&card instanceof LinearLayout){TextView current=(TextView)((LinearLayout)card).getChildAt(1);current.setText(error==null?"Aktuell: "+formatValue(value,p.unit):"Aktuell: "+error);}}}else for(int i=0;i<valuesList.getChildCount();i++){View group=valuesList.getChildAt(i);if(group instanceof LinearLayout)for(int r=1;r<((LinearLayout)group).getChildCount();r++){LinearLayout row=(LinearLayout)((LinearLayout)group).getChildAt(r);for(int j=0;j<row.getChildCount();j++){View v=row.getChildAt(j);if(v.getTag()==p){if(error==null){if(v instanceof GaugeView)((GaugeView)v).setReading(value);else if(v instanceof TextView)((TextView)v).setText(p.name+"\n"+formatValue(value,p.unit));}break;}}}}if(error!=null)addLog(p.name+": "+error);});}
  private String formatValue(Double value,String unit){return String.format(Locale.ROOT,"%."+(Math.abs(value-Math.rint(value))<0.0001?0:2)+"f %s",value,unit).trim();}
  private byte[] readFrame(int slave,int function,int address,int count){byte[] b={(byte)slave,(byte)function,(byte)(address>>8),(byte)address,(byte)(count>>8),(byte)count,0,0};int crc=crc16(b,6);b[6]=(byte)crc;b[7]=(byte)(crc>>8);return b;}
  private int registerCount(String fmt){String f=fmt.toLowerCase(Locale.ROOT);return (f.contains("32")||f.contains("ulong")||f.contains("long-")||f.contains("48")||f.contains("date")||f.contains("time"))?2:1;}
  private double decode(int[] r,String fmt){String f=fmt.toLowerCase(Locale.ROOT);if(r.length==0)throw new IllegalArgumentException("leere Antwort");if(f.contains("cdab")||f.contains("little_end_32")){if(r.length<2)throw new IllegalArgumentException("32-bit-Wert unvollständig");return (((long)r[1]<<16)|(r[0]&65535));}if(f.contains("32")||f.contains("ulong")||f.contains("long-")){if(r.length<2)throw new IllegalArgumentException("32-bit-Wert unvollständig");return (((long)r[0]<<16)|(r[1]&65535));}if(f.contains("short")&&r[0]>=32768)return r[0]-65536;return r[0];}
  private int crc16(byte[] b,int len){int crc=65535;for(int i=0;i<len;i++){crc^=b[i]&255;for(int j=0;j<8;j++)crc=(crc&1)!=0?(crc>>1)^0xA001:crc>>1;}return crc&65535;}
  private boolean validCrc(byte[] b,int len){int got=(b[len-2]&255)|((b[len-1]&255)<<8);return got==crc16(b,len-2);}

  private void showSettings(){
    if(selectedModel==null){say("Zuerst ein Reglermodell wählen.");return;}
    if(gatt==null||tx==null){say("Für lokale Einstellungen zuerst verbinden.");return;}
    pollingLive=false;handler.removeCallbacks(livePoll);liveButton.setText("Live-Daten starten");
    ScrollView page=new ScrollView(this);LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(16),dp(14),dp(16),dp(24));content.setBackgroundColor(BG);page.addView(content);settingsPage=content;
    LinearLayout header=card();Button back=button("← Zurück zu den Live-Daten",()->{setContentView(mainView);settingsPage=null;});back.setBackgroundTintList(ColorStateList.valueOf(BLUE));header.addView(back);sectionTitle(header,"Einstellungen","Aktueller Wert und gewünschter neuer Wert. Registeradressen bleiben im Hintergrund.");content.addView(header);
    settingInputs.clear();ArrayList<Point> settingPoints=new ArrayList<>();
    try(android.database.sqlite.SQLiteDatabase d=db();android.database.Cursor c=d.rawQuery("SELECT variableNameE,dataIdentifier,address,dataFormat,decimal,unit,controlFormula,acquisitionFormula,groupId FROM hn_product_variable WHERE productId=? AND register=? AND type=0 AND dataFormat IN (?,?,?) ORDER BY groupId,sort",new String[]{String.valueOf(selectedModel.id),"4","ushort","short","bit"})){
      while(c.moveToNext()){
        String name=c.getString(0),key=c.getString(1),address=c.getString(2),fmt=c.getString(3),unit=c.getString(5),control=c.getString(6),acq=c.getString(7);int groupId=parse(c.getString(8),0);
        if(name==null||address==null||fmt==null)continue;
        Point p=new Point(name,key,3,parse(address,0),fmt,parse(c.getString(4),0),unit==null?"":unit,control==null?"":control,acq==null?"":acq);
        LinearLayout card=card();TextView label=text(name,16);label.setTextColor(NAVY);label.setTypeface(null,android.graphics.Typeface.BOLD);card.addView(label);
        TextView current=text("Aktuell: wird vom Regler gelesen",13);current.setTag("current");card.addView(current);
        EditText input=new EditText(this);input.setSingleLine(true);input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED|InputType.TYPE_NUMBER_FLAG_DECIMAL);input.setHint("Neuen Wert eingeben"+(p.unit.isEmpty()?"":" ("+p.unit+")"));card.addView(input);card.setTag(p);settingInputs.put(p,input);settingPoints.add(p);content.addView(card);
      }
      if(settingPoints.isEmpty())content.addView(text("Für dieses Modell sind keine unterstützten Einstellungen hinterlegt.",14));
      Button apply=button("Einstellung übernehmen",()->confirmSettings());apply.setBackgroundTintList(ColorStateList.valueOf(TEAL));LinearLayout bottom=card();bottom.addView(apply);content.addView(bottom);setContentView(page);
      pendingReads.clear();pendingReads.addAll(settingPoints);if(!busy){busy=true;nextRead();}say("Einstellungen geöffnet; aktuelle Werte werden gelesen.");
    }catch(Exception e){say("Einstellungen konnten nicht geladen werden: "+e.getMessage());}
  }
  private void confirmSettings(){ArrayList<SettingWrite> changed=new ArrayList<>();for(Map.Entry<Point,EditText> entry:settingInputs.entrySet()){String s=entry.getValue().getText().toString().trim();if(s.isEmpty())continue;try{Point p=entry.getKey();double n=Double.parseDouble(s);double raw=p.controlFormula.isEmpty()?n*Math.pow(10,p.decimals):applyFormula(p.controlFormula,n);changed.add(new SettingWrite(p,(int)Math.round(raw)));}catch(Exception e){say("Ungültiger Wert bei "+entry.getKey().name);return;}}if(changed.isEmpty()){say("Trage zuerst mindestens einen neuen Wert ein.");return;}new AlertDialog.Builder(this).setTitle("Einstellungen übernehmen?").setMessage(changed.size()+" Wert(e) werden an den Regler geschrieben. Prüfe die Eingaben und das ausgewählte Modell.").setNegativeButton("Abbrechen",null).setPositiveButton("Übernehmen",(d,w)->{settingWrites.clear();settingWrites.addAll(changed);settingsWriteTotal=changed.size();settingsWriteDone=0;writeNextSetting();}).show();}
  private void writeNextSetting(){if(settingWrites.isEmpty()){say("Einstellungen übernommen: "+settingsWriteDone+" von "+settingsWriteTotal+" bestätigt.");return;}SettingWrite sw=settingWrites.removeFirst();writeSetting(sw.point,sw.value);}
  private void promptSetting(Point p){
    EditText input=new EditText(this);input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED|InputType.TYPE_NUMBER_FLAG_DECIMAL);input.setHint("Rohwert für Register "+p.address);
    new AlertDialog.Builder(this).setTitle(p.name).setMessage("Wert in der angezeigten Einheit eingeben. Die App rechnet mit dem im Modell hinterlegten Dezimalfaktor. Vor dem Schreiben prüfen, dass das gewählte Modell wirklich zum Regler passt.").setView(input).setNegativeButton("Abbrechen",null).setPositiveButton("Weiter",(d,w)->{
      String value=input.getText().toString().trim();try{double displayed=Double.parseDouble(value);double scaled=p.controlFormula.isEmpty()?displayed*Math.pow(10,p.decimals):applyFormula(p.controlFormula,displayed);int raw=(int)Math.round(scaled);String msg="Es wird der Rohwert "+raw+" an Register "+p.address+" gesendet. Das kann den Ladebetrieb verändern.";
        new AlertDialog.Builder(this).setTitle("Einstellung wirklich schreiben?").setMessage(msg).setNegativeButton("Abbrechen",null).setPositiveButton("Schreiben",(dd,ww)->writeSetting(p,raw)).show();
      }catch(Exception e){say("Bitte eine gültige Zahl eingeben.");}
    }).show();
  }
  private void writeSetting(Point p,int value){
    if(gatt==null||tx==null){say("Verbindung getrennt; nichts geschrieben.");return;}
    if(p.address<1||p.address>65536||value<Short.MIN_VALUE||value>65535){say("Wert oder Register liegt außerhalb des unterstützten 16-Bit-Bereichs.");return;}
    int address=p.address-1;byte[] b={(byte)1,6,(byte)(address>>8),(byte)address,(byte)(value>>8),(byte)value,0,0};int crc=crc16(b,6);b[6]=(byte)crc;b[7]=(byte)(crc>>8);response.reset();pendingWrite=b;pendingWriteName=p.name;
    try{write(b);say("Schreibbefehl gesendet; warte auf Modbus-Bestätigung …");final byte[] expected=b;writeTimeout=()->{if(pendingWrite==expected){pendingWrite=null;pendingWriteName=null;say("Keine Bestätigung erhalten. Bitte den Reglerwert prüfen.");}};handler.postDelayed(writeTimeout,2500);}catch(Exception e){pendingWrite=null;pendingWriteName=null;say("Schreibbefehl fehlgeschlagen: "+e.getMessage());}
  }
  private double applyFormula(String formula,double value){String expression=formula.replace("%s",Double.toString(value)).replace(" ","");if(expression.matches("[-+]?\\d+(\\.\\d+)?([*/][-+]?\\d+(\\.\\d+)?)?")){java.util.regex.Matcher m=java.util.regex.Pattern.compile("^([-+]?\\d+(?:\\.\\d+)?)(?:([*/])([-+]?\\d+(?:\\.\\d+)?))?$").matcher(expression);if(m.matches()){double result=Double.parseDouble(m.group(1));if(m.group(2)!=null){double n=Double.parseDouble(m.group(3));result="*".equals(m.group(2))?result*n:result/n;}return result;}}throw new IllegalArgumentException("Dieses Einstellungsformat kann die App nicht sicher umrechnen.");}
  @Override protected void onDestroy(){stopScan();disconnect();super.onDestroy();}

  private static class Device{final BluetoothDevice device;final String name,address;int rssi;Device(BluetoothDevice d,String n,int s){device=d;name=n;address=d.getAddress();rssi=s;}}
  private static class Model{final int id;final String name;Model(int i,String n){id=i;name=n==null?"EPEVER":n;}}
  private static class Point{final String name,key,format,unit,controlFormula,acquisitionFormula;final int function,address,decimals;Point(String n,String k,int f,int a,String fm,int d,String u,String cf,String af){name=n;key=k;function=f;address=a;format=fm;decimals=d;unit=u;controlFormula=cf;acquisitionFormula=af;}}
  private static class SettingWrite{final Point point;final int value;SettingWrite(Point p,int v){point=p;value=v;}}
  private class GaugeView extends View{
    private final Point point;private final Paint paint=new Paint(3);private double shown=0,target=0;private boolean hasValue=false;
    GaugeView(Context c,Point p){super(c);point=p;setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
    void setReading(double v){double old=shown;target=v;hasValue=true;ValueAnimator a=ValueAnimator.ofFloat(0,1);a.setDuration(850);a.addUpdateListener(x->{float t=(float)x.getAnimatedValue();shown=old+(target-old)*t;invalidate();});a.start();}
    private double max(){String k=point.key.toLowerCase(Locale.ROOT);if(k.contains("soc"))return 100;if(k.endsWith("_v"))return 200;if(k.endsWith("_i"))return 100;if(k.endsWith("_w"))return 2500;if(k.contains("temp"))return 100;if(k.contains("powergeneration")||k.contains("powerconsumption"))return 1000;return 100;}
    @Override protected void onDraw(Canvas c){super.onDraw(c);float cx=getWidth()/2f,cy=dp(94),radius=Math.min(getWidth()*0.39f,dp(61));RectF arc=new RectF(cx-radius,cy-radius,cx+radius,cy+radius);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(8));paint.setStrokeCap(Paint.Cap.ROUND);paint.setColor(Color.rgb(225,233,238));c.drawArc(arc,150,240,false,paint);paint.setColor(TEAL);float fraction=(float)Math.max(0,Math.min(1,shown/max()));c.drawArc(arc,150,240*fraction,false,paint);
      float angle=(float)Math.toRadians(150+240*fraction);paint.setColor(NAVY);paint.setStrokeWidth(dp(2));paint.setStyle(Paint.Style.STROKE);c.drawLine(cx,cy,cx+(float)Math.cos(angle)*radius*.76f,cy+(float)Math.sin(angle)*radius*.76f,paint);paint.setStyle(Paint.Style.FILL);c.drawCircle(cx,cy,dp(4),paint);
      paint.setColor(INK);paint.setTextAlign(Paint.Align.CENTER);paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));paint.setTextSize(dp(13));c.drawText(point.name,cx,dp(18),paint);paint.setTextSize(dp(16));c.drawText(hasValue?formatValue(shown,point.unit):"—",cx,cy+dp(32),paint);
    }
  }
}
