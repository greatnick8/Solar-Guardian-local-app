package de.andre.solarguardianlocal;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.content.pm.PackageManager;
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
  private BluetoothAdapter adapter;
  private BluetoothLeScanner scanner;
  private BluetoothGatt gatt;
  private BluetoothGattCharacteristic tx, rx;
  private ScanCallback scanCallback;
  private TextView status, log;
  private Button liveButton;
  private Spinner modelPicker;
  private LinearLayout deviceList, valuesList;
  private Model selectedModel;
  private Point currentPoint;
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

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    BluetoothManager manager=(BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
    adapter=manager==null?null:manager.getAdapter();
    copyDatabase();
    loadModels();
    buildUi();
    requestBluetoothPermission();
  }

  private void buildUi() {
    ScrollView scroll=new ScrollView(this);
    LinearLayout page=new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(18,16,18,16);
    page.addView(text("Solar Guardian Local",23));
    page.addView(text("Lokale BLE-Verbindung · keine Anmeldung · kein Cloudzugriff",14));
    status=text("Bereit – Modell wählen und BLE-Gerät suchen",15); page.addView(status);
    modelPicker=new Spinner(this);
    ArrayList<String> names=new ArrayList<>(); for(Model m:models) names.add(m.name);
    modelPicker.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));
    modelPicker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
      public void onNothingSelected(android.widget.AdapterView<?> p){}
      public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){ if(pos>=0&&pos<models.size()){selectedModel=models.get(pos); loadPoints();}}
    });
    page.addView(modelPicker);
    LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
    row.addView(button("BLE-Geräte suchen",()->startScan()));
    row.addView(button("Trennen",()->disconnect())); page.addView(row);
    page.addView(text("Gefundene Geräte – antippen zum Auswählen",16));
    deviceList=new LinearLayout(this); deviceList.setOrientation(LinearLayout.VERTICAL); page.addView(deviceList);
    page.addView(button("Ausgewähltes Gerät verbinden",()->connectSelected()));
    liveButton=button("Live-Daten starten",()->toggleLive()); page.addView(liveButton);
    page.addView(button("Einstellungen anzeigen / ändern",()->showSettings()));
    page.addView(text("Lokale Messwerte",18)); valuesList=new LinearLayout(this); valuesList.setOrientation(LinearLayout.VERTICAL); page.addView(valuesList);
    page.addView(text("Verbindungsprotokoll",16)); log=text("Noch keine Verbindung.",12); page.addView(log);
    scroll.addView(page); setContentView(scroll);
  }
  private TextView text(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setPadding(0,8,0,8);return t;}
  private Button button(String s,Runnable action){Button b=new Button(this);b.setText(s);b.setOnClickListener(v->action.run());return b;}
  private void say(String s){runOnUiThread(()->{status.setText(s); log.append("\n"+s);});}

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
    valuesList.removeAllViews();
    try(android.database.sqlite.SQLiteDatabase d=db();android.database.Cursor c=d.rawQuery("SELECT variableNameE,dataIdentifier,register,address,dataFormat,decimal,unit FROM hn_product_variable WHERE productId=? AND type=0 ORDER BY groupId,sort",new String[]{String.valueOf(selectedModel.id)})){
      while(c.moveToNext()){
        String name=c.getString(0), key=c.getString(1), reg=c.getString(2), addr=c.getString(3), fmt=c.getString(4), dec=c.getString(5), unit=c.getString(6);
        if(name==null||key==null||addr==null||fmt==null)continue;
        if(!isLiveKey(key))continue;
        Point p=new Point(name,key,readFunction(parse(reg,3)),parse(addr,0),fmt,parse(dec,0),unit==null?"":unit,"");
        TextView v=text(name+": —",16); v.setTag(p); valuesList.addView(v);
      }
      if(valuesList.getChildCount()==0)valuesList.addView(text("Für dieses Modell sind keine Live-Felder in der Offline-Datenbank markiert.",14));
    }catch(Exception e){valuesList.addView(text("Modell-Daten konnten nicht gelesen werden: "+e.getMessage(),14));}
  }
  private boolean isLiveKey(String key){String k=key.toLowerCase(Locale.ROOT);return k.startsWith("pv_")||k.startsWith("bat_")||k.startsWith("load_")||k.contains("powergeneration")||k.contains("powerconsumption")||k.contains("devicetem");}
  private int parse(String s,int fallback){try{return Integer.parseInt(s);}catch(Exception e){return fallback;}}
  // The vendor database uses 3=input register (Modbus FC4) and 4=output register (FC3).
  private int readFunction(int register){if(register==3)return 4;if(register==4)return 3;if(register==1)return 2;if(register==0)return 1;return register;}

  private String[] permissions(){return Build.VERSION.SDK_INT>=31?new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT}:new String[]{Manifest.permission.ACCESS_FINE_LOCATION};}
  private void requestBluetoothPermission(){ArrayList<String> missing=new ArrayList<>();for(String p:permissions())if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED)missing.add(p);if(!missing.isEmpty())requestPermissions(missing.toArray(new String[0]),PERMISSION_REQUEST);}
  private boolean permitted(){for(String p:permissions())if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED)return false;return true;}
  @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==PERMISSION_REQUEST){boolean ok=g.length>0;for(int x:g)ok&=x==PackageManager.PERMISSION_GRANTED;say(ok?"Bluetooth-Berechtigung erteilt.":"Bitte Bluetooth-Berechtigung erlauben.");}}

  private void startScan(){
    if(!permitted()){requestBluetoothPermission();return;} if(adapter==null||!adapter.isEnabled()){say("Bitte Bluetooth einschalten.");return;}
    scanner=adapter.getBluetoothLeScanner(); if(scanner==null){say("BLE-Scanner nicht verfügbar.");return;}
    stopScan();found.clear();deviceList.removeAllViews();say("Suche 12 Sekunden nach BLE-Geräten …");
    scanCallback=new ScanCallback(){@Override public void onScanResult(int type,ScanResult r){BluetoothDevice d=r.getDevice();for(Device old:found)if(old.address.equals(d.getAddress()))return;String n="BLE-Gerät";try{if(d.getName()!=null)n=d.getName();}catch(SecurityException ignored){}final String deviceName=n;Device item=new Device(d,deviceName,r.getRssi());found.add(item);runOnUiThread(()->deviceList.addView(button(deviceName+" · "+item.address+" · "+item.rssi+" dBm",()->{selectedDevice=item;say("Ausgewählt: "+item.name);})));}};
    try{scanner.startScan(scanCallback);handler.postDelayed(()->{stopScan();say("Suche beendet: "+found.size()+" Gerät(e)");},12000);}catch(Exception e){say("Scanfehler: "+e.getMessage());}
  }
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
  private void readLive(){if(gatt==null||tx==null||rx==null){say("Zuerst mit dem BLE-Stick verbinden.");return;}if(busy)return;pendingReads.clear();for(int i=0;i<valuesList.getChildCount();i++){View v=valuesList.getChildAt(i);if(v.getTag() instanceof Point)pendingReads.add((Point)v.getTag());}if(pendingReads.isEmpty()){say("Keine lesbaren Live-Felder für dieses Modell.");return;}say("Lese lokale Reglerwerte per Modbus …");busy=true;nextRead();}
  private void nextRead(){if(pendingReads.isEmpty()){currentPoint=null;busy=false;say("Lesezyklus beendet.");if(pollingLive)handler.postDelayed(livePoll,10000);return;}currentPoint=pendingReads.removeFirst();final Point expectedPoint=currentPoint;response.reset();byte[] req=readFrame(1,currentPoint.function,currentPoint.address-1,registerCount(currentPoint.format));try{write(req);requestTimeout=()->{if(currentPoint==expectedPoint){appendValue(expectedPoint,null,"Keine Antwort / Zeitüberschreitung");currentPoint=null;nextRead();}};handler.postDelayed(requestTimeout,1800);}catch(Exception e){appendValue(currentPoint,null,"Senden fehlgeschlagen");currentPoint=null;nextRead();}}
  private synchronized void write(byte[] bytes){if(gatt==null||tx==null)throw new IllegalStateException("Nicht verbunden");if(Build.VERSION.SDK_INT>=33){int mode=(tx.getProperties()&BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)!=0?BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE:BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;gatt.writeCharacteristic(tx,bytes,mode);}else{tx.setWriteType((tx.getProperties()&BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)!=0?BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE:BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);tx.setValue(bytes);gatt.writeCharacteristic(tx);}}
  private void consumeResponse(){byte[] b; synchronized(response){b=response.toByteArray();}if(b.length<5)return;int fc=b[1]&255;int expected=(fc==5||fc==6||fc==15||fc==16)?8:(fc>=128?5:(b[2]&255)+5);if(b.length<expected)return;
    if(pendingWrite!=null){byte[] sent=pendingWrite;String name=pendingWriteName;pendingWrite=null;pendingWriteName=null;if(writeTimeout!=null)handler.removeCallbacks(writeTimeout);writeTimeout=null;boolean ok=validCrc(b,expected)&&b[1]==sent[1]&&b[2]==sent[2]&&b[3]==sent[3]&&b[4]==sent[4]&&b[5]==sent[5];say(ok?"Einstellung bestätigt: "+name:"Keine gültige Schreibbestätigung für "+name);return;}
    if(currentPoint==null)return;Point done=currentPoint;if(requestTimeout!=null)handler.removeCallbacks(requestTimeout);requestTimeout=null;try{if(!validCrc(b,expected))throw new Exception("CRC ungültig");int[] regs=new int[(b[2]&255)/2];for(int i=0;i<regs.length;i++)regs[i]=((b[3+i*2]&255)<<8)|(b[4+i*2]&255);double value=decode(regs,done.format);if(done.decimals>0)value/=Math.pow(10,done.decimals);appendValue(done,value,null);}catch(Exception e){appendValue(done,null,"Antwort nicht lesbar: "+e.getMessage());}currentPoint=null;if(busy)handler.postDelayed(this::nextRead,130);}
  private void appendValue(Point p,Double value,String error){runOnUiThread(()->{for(int i=0;i<valuesList.getChildCount();i++){View v=valuesList.getChildAt(i);if(v.getTag()==p){((TextView)v).setText(p.name+": "+(error==null?String.format(Locale.ROOT,"%.2f %s",value,p.unit):error));break;}}if(error!=null)log.append("\n"+p.name+": "+error);});}
  private byte[] readFrame(int slave,int function,int address,int count){byte[] b={(byte)slave,(byte)function,(byte)(address>>8),(byte)address,(byte)(count>>8),(byte)count,0,0};int crc=crc16(b,6);b[6]=(byte)crc;b[7]=(byte)(crc>>8);return b;}
  private int registerCount(String fmt){String f=fmt.toLowerCase(Locale.ROOT);return (f.contains("32")||f.contains("ulong")||f.contains("long-")||f.contains("48")||f.contains("date")||f.contains("time"))?2:1;}
  private double decode(int[] r,String fmt){String f=fmt.toLowerCase(Locale.ROOT);if(r.length==0)throw new IllegalArgumentException("leere Antwort");if(f.contains("cdab")||f.contains("little_end_32")){if(r.length<2)throw new IllegalArgumentException("32-bit-Wert unvollständig");return ((long)r[0]<<16)|(r[1]&65535);}if(f.contains("32")||f.contains("ulong")||f.contains("long-")){if(r.length<2)throw new IllegalArgumentException("32-bit-Wert unvollständig");return ((long)r[0]<<16)|(r[1]&65535);}if(f.contains("short")&&r[0]>=32768)return r[0]-65536;return r[0];}
  private int crc16(byte[] b,int len){int crc=65535;for(int i=0;i<len;i++){crc^=b[i]&255;for(int j=0;j<8;j++)crc=(crc&1)!=0?(crc>>1)^0xA001:crc>>1;}return crc&65535;}
  private boolean validCrc(byte[] b,int len){int got=(b[len-2]&255)|((b[len-1]&255)<<8);return got==crc16(b,len-2);}

  private void showSettings(){
    if(selectedModel==null){say("Zuerst ein Reglermodell wählen.");return;}
    if(gatt==null||tx==null){say("Für lokale Einstellungen zuerst verbinden.");return;}
    pollingLive=false;handler.removeCallbacks(livePoll);liveButton.setText("Live-Daten starten");
    valuesList.removeAllViews();
    try(android.database.sqlite.SQLiteDatabase d=db();android.database.Cursor c=d.rawQuery("SELECT variableNameE,dataIdentifier,address,dataFormat,decimal,unit,controlFormula FROM hn_product_variable WHERE productId=? AND register=? AND type=0 AND dataFormat IN (?,?,?) ORDER BY groupId,sort",new String[]{String.valueOf(selectedModel.id),"4","ushort","short","bit"})){
      while(c.moveToNext()){
        String name=c.getString(0),key=c.getString(1),address=c.getString(2),fmt=c.getString(3),unit=c.getString(5),control=c.getString(6);
        if(name==null||address==null||fmt==null)continue;
        Point p=new Point(name,key,3,parse(address,0),fmt,parse(c.getString(4),0),unit==null?"":unit,control==null?"":control);
        valuesList.addView(button(name+"  ·  Register "+address+(unit==null||unit.isEmpty()?"":" · "+unit),()->promptSetting(p)));
      }
      if(valuesList.getChildCount()==0)valuesList.addView(text("Für dieses Modell sind keine beschreibbaren Register hinterlegt.",14));
      say("Lokale Registerliste geladen. Schreibzugriffe werden erst nach deiner Bestätigung gesendet.");
    }catch(Exception e){say("Einstellungen konnten nicht geladen werden: "+e.getMessage());}
  }
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

  private static class Device{final BluetoothDevice device;final String name,address;final int rssi;Device(BluetoothDevice d,String n,int s){device=d;name=n;address=d.getAddress();rssi=s;}}
  private static class Model{final int id;final String name;Model(int i,String n){id=i;name=n==null?"EPEVER":n;}}
  private static class Point{final String name,key,format,unit,controlFormula;final int function,address,decimals;Point(String n,String k,int f,int a,String fm,int d,String u,String cf){name=n;key=k;function=f;address=a;format=fm;decimals=d;unit=u;controlFormula=cf;}}
}
