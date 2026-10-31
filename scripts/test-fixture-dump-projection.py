#!/usr/bin/env python3
"""Compile/run the actual Java projection. Host synthetic checks, not Android runtime proof."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import sys

ROOT = Path(__file__).resolve().parents[1]
source = Path(sys.argv[1]) if len(sys.argv) == 2 else ROOT / 'app/src/androidTest/java/com/rdp/sync/FixtureDumpProjection.java'
if len(sys.argv) > 2:
    raise SystemExit('usage: test-fixture-dump-projection.py [actual-java-source]')
java_home = os.environ.get('JAVA_HOME')
def tool(name):
    candidate = Path(java_home) / 'bin' / name if java_home else None
    value = str(candidate) if candidate and candidate.is_file() else shutil.which(name)
    if not value:
        raise SystemExit(name + ' required (JAVA_HOME/bin or PATH); tests cannot skip')
    return value
javac, java = tool('javac'), tool('java')
HARNESS = r'''
import com.rdp.sync.FixtureDumpProjection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

public class ProjectionHostTest {
  static int count;
  static final String IME = "mCurMethodId=gboard/IME\nmCurId=gboard/IME mHaveConnection=true mBoundToMethod=true\nmCurMethod=Invoker@1\nInput method service state for Gboard@1:\n mSettingsObserver=SettingsObserver{mShowImeWithHardKeyboard=2}\n mNavigationBarController=ImeNavigationBarController\n";
  static final String ROW = "_id:1 name:show_ime_with_hard_keyboard value:1 package:android default:1 defaultSystemSet:true\n";
  static final String SETTINGS = "SECURE SETTINGS (user 0)\n" + ROW + "SYSTEM SETTINGS (user 0)\n";
  static final String END = "RDP_FIXTURE_DUMP_EXIT=0\n";
  static void check(boolean condition, String why) { if (!condition) throw new AssertionError(why); }
  static String hash(byte[] data) throws Exception {
    StringBuilder h = new StringBuilder();
    for(byte b: MessageDigest.getInstance("SHA-256").digest(data)) h.append(String.format("%02x", b & 255));
    return h.toString();
  }
  static FixtureDumpProjection feed(String scope, String text, int chunk) {
    FixtureDumpProjection p=new FixtureDumpProjection(scope);byte[] data=text.getBytes(StandardCharsets.UTF_8);
    for(int off=0;off<data.length;off+=chunk) {
      int n=Math.min(chunk,data.length-off);byte[] part=new byte[n];System.arraycopy(data,off,part,0,n);p.accept(part,n);
    }
    return p;
  }
  static void good(String scope,String text,int chunk,String expected,int rows) throws Exception {
    FixtureDumpProjection p=feed(scope,text,chunk);check(p.finish(true).equals(expected),"exact projection");
    Map<String,Object> r=p.receipt();byte[] raw=text.getBytes(StandardCharsets.UTF_8);
    check(r.get("complete").equals(true),"complete");check(r.get("producer_eof").equals(true),"EOF");
    check(((Number)r.get("source_bytes")).longValue()==raw.length,"source bytes");check(r.get("source_sha256").equals(hash(raw)),"full raw hash");
    check(((Number)r.get("selected_bytes")).intValue()==expected.getBytes(StandardCharsets.UTF_8).length,"selected bytes");
    check(((Number)r.get("exit_trailers")).intValue()==1,"unique exit");
    check(((Number)r.get("lines")).longValue()==text.chars().filter(c->c=='\n').count(),"all lines consumed");
    for(String key:scope.equals("ime")?new String[]{"service_headers","service_completions","selected_ids","bound_ids","method_ids"}:new String[]{"secure_sections","system_sections"}) check(((Number)r.get(key)).intValue()==1,"unique "+key);
    check(((Number)r.get(scope.equals("ime")?"observer_rows":"setting_rows")).intValue()==rows,"row count");
    check(p.finish(true).equals(expected),"idempotent finish");count++;
  }
  static void bad(String label,String scope,String text,int chunk,boolean eof,String reason) throws Exception {
    FixtureDumpProjection p=new FixtureDumpProjection(scope);boolean rejected=false;
    byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
    try {
      for(int off=0;off<bytes.length;off+=chunk){int n=Math.min(chunk,bytes.length-off);byte[] part=new byte[n];System.arraycopy(bytes,off,part,0,n);p.accept(part,n);}
      p.finish(eof);
    } catch(IllegalStateException expected) { rejected=true;try {p.finish(false);}catch(IllegalStateException alsoExpected){} }
    check(rejected,label+" accepted");Map<String,Object> r=p.receipt();
    check(r.get("complete").equals(false),label+" complete");check(!r.get("failure").equals(""),label+" no reason");
    if(reason!=null) check(r.get("failure").toString().contains(reason),label+" wrong reason: "+r);
    check(((Number)r.get("source_bytes")).longValue()<=FixtureDumpProjection.MAX_SOURCE_BYTES+(long)chunk,"source bound");
    check(((Number)r.get("selected_bytes")).intValue()<=FixtureDumpProjection.MAX_SELECTED_BYTES,"selected bound");
    check(p.projection()!=null,"partial accessible");
    int consumed=((Number)r.get("source_bytes")).intValue();
    check(r.get("source_sha256").equals(hash(java.util.Arrays.copyOf(bytes,consumed))),label+" failure raw hash");
    if(label.equals("timeout partial")) check(p.projection().equals(text),"timeout retains exact selected partial");
    count++;
  }

  static void shellExecution(String scope, String lateError, int status) throws Exception {
    java.nio.file.Path dir=java.nio.file.Files.createTempDirectory("projection-shell-");
    try {
      String body=scope.equals("ime")?IME:SETTINGS;
      java.nio.file.Files.writeString(dir.resolve("body"),body,StandardCharsets.UTF_8);
      String target=scope.equals("ime")?"input_method":"settings";
      String stub="#!/bin/sh\n[ \"$1\" = '-t' ] && [ \"$2\" = '2' ] && [ \"$3\" = '"+target+"' ] && [ \"$#\" = '3' ] || exit 99\n/bin/cat body\n";
      if(!lateError.isEmpty()) stub+="printf '%s\\n' '"+lateError+"' >&2\n";
      stub+="exit "+status+"\n";
      java.nio.file.Path command=dir.resolve("dumpsys");java.nio.file.Files.writeString(command,stub,StandardCharsets.UTF_8);
      check(command.toFile().setExecutable(true),"stub executable");
      Process process=Runtime.getRuntime().exec("sh",new String[]{"PATH="+dir+":/usr/bin:/bin"},dir.toFile());
      byte[] output;
      try {
        try(java.io.OutputStream stdin=process.getOutputStream()) {
          stdin.write(FixtureDumpProjection.shellScript("dumpsys -t 2 "+target).getBytes(StandardCharsets.UTF_8));
        }
        output=process.getInputStream().readAllBytes(); // real EOF only after the producer exits
        check(process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS),"shell EOF/termination");
        check(process.exitValue()==0,"wrapper status is not producer status");
        check(process.getErrorStream().readAllBytes().length==0,"stderr merged into projection stream");
      } finally {process.destroyForcibly();}
      String expected=body+(lateError.isEmpty()?"":lateError+"\n")+"\nRDP_FIXTURE_DUMP_EXIT="+status+"\n";
      check(new String(output,StandardCharsets.UTF_8).equals(expected),"real wrapper bytes including late stderr/trailer");
      FixtureDumpProjection projection=new FixtureDumpProjection(scope);
      for(byte b:output)projection.accept(new byte[]{b},1);
      boolean rejected=false;
      try{projection.finish(true);}catch(IllegalStateException fail){rejected=true;}
      check(rejected==(!lateError.isEmpty()||status!=0),"actual shell producer failure accepted");
      Map<String,Object> receipt=projection.receipt();
      check(receipt.get("source_sha256").equals(hash(output)),"shell full stream hash");
      check(((Number)receipt.get("source_bytes")).intValue()==output.length,"shell full stream bytes");
      check(receipt.get("producer_eof").equals(true),"real shell EOF receipt");
      check(((Number)receipt.get("exit_status")).intValue()==status,"original producer exit status");
      check(((Number)receipt.get("exit_trailers")).intValue()==1,"generated trailer unique");
      check(receipt.get("complete").equals(!rejected),"shell completion");
      check(projection.projection().equals(body),"minimal shell selected projection");
      count++;
    } finally {
      try(java.util.stream.Stream<java.nio.file.Path> paths=java.nio.file.Files.walk(dir)) {
        for(java.nio.file.Path file:paths.sorted(java.util.Comparator.reverseOrder()).toList())java.nio.file.Files.delete(file);
      }
    }
  }

  public static void main(String[] args) throws Exception {
    good("ime",IME+END,1,IME,1);
    good("ime",IME+END,8192,IME,1);
    good("settings",SETTINGS+END,1,SETTINGS,1);
    good("settings",SETTINGS+END,4096,SETTINGS,1);
    String prefix="unrelated="+"x".repeat(300000)+"\n";
    good("ime",prefix+IME+END,997,IME,1);
    good("settings",prefix+SETTINGS+END,8191,SETTINGS,1);
    good("ime","diagnostic=汉字😀\n"+IME+END,1,IME,1);
    for(String marker:new String[]{"DUMP TIMEOUT","Failed to dump","Permission Denial","Error dumping service"}) {
      bad("late producer error","ime",IME+"unrelated="+"x".repeat(20000)+marker+"\n"+END,1,true,"producer-error");
    }
    bad("hidden indented manager","ime",IME+" ".repeat(9000)+"mCurMethodId=other/IME\n"+END,17,true,"ambiguous-long-whitespace-line");
    bad("long target row","settings",SETTINGS.replace(ROW,"_id:1 "+"x".repeat(9000)+" name:show_ime_with_hard_keyboard value:1\n")+END,23,true,"relevant-line-limit");
    bad("duplicate manager","ime",IME+"mCurMethodId=other/IME\n"+END,13,true,"nonunique");
    bad("multiple service blocks","ime",IME+IME+END,31,true,"nonunique");
    bad("nested service","ime",IME.replace(" mSettingsObserver=", "Input method service state for Other:\n mSettingsObserver=")+END,7,true,"nested-service");
    bad("duplicate observer","ime",IME+"mSettingsObserver=SettingsObserver{mShowImeWithHardKeyboard=2}\n"+END,19,true,null);
    bad("long relevant row","ime",IME.replace("mCurMethodId=gboard/IME","mCurMethodId="+"x".repeat(9000))+END,17,true,"relevant-line-limit");
    bad("late target duplicate","settings",SETTINGS.replace("SYSTEM SETTINGS",ROW+"SYSTEM SETTINGS")+END,7,true,"nonunique");
    bad("hidden long target duplicate","settings",SETTINGS.replace("SYSTEM SETTINGS","_id:2 "+"x".repeat(9000)+" name:show_ime_with_hard_keyboard value:1\nSYSTEM SETTINGS")+END,31,true,null);
    bad("duplicate secure section","settings",SETTINGS+SETTINGS+END,512,true,"nonunique");
    bad("missing system section","settings","SECURE SETTINGS (user 0)\n"+ROW+END,11,true,"nonunique");
    bad("no EOF","ime",IME+END,64,false,"incomplete-producer");
    bad("timeout partial","ime",IME.substring(0,IME.indexOf(" mNavigation")),128,false,"incomplete-producer");
    bad("missing exit","ime",IME,19,true,"missing-or-failed-exit");
    bad("nonzero exit","ime",IME+"RDP_FIXTURE_DUMP_EXIT=1\n",23,true,"missing-or-failed-exit");
    bad("duplicate forged exit","ime",END+IME+END,13,true,"missing-or-failed-exit");
    bad("trailing output","ime",IME+END+"unexpected\n",11,true,"missing-or-failed-exit");
    bad("malformed exit","ime",IME+"RDP_FIXTURE_DUMP_EXIT=oops\n",10,true,"malformed-exit");
    bad("source bound","ime",IME+"x".repeat(FixtureDumpProjection.MAX_SOURCE_BYTES)+"\n"+END,4096,true,"total-source-limit");
    bad("selected bound","ime",("mCurMethodId="+"x".repeat(8000)+"\n").repeat(10)+IME+END,2048,true,"selected-output-limit");
    FixtureDumpProjection closed=feed("ime",IME+END,32);closed.finish(true);boolean denied=false;
    try {closed.accept(new byte[]{1},1);} catch(IllegalStateException expected){denied=true;}check(denied,"write after finish");count++;
    shellExecution("ime","",0);
    shellExecution("settings","",0);
    shellExecution("ime","DUMP TIMEOUT late stderr",0);
    shellExecution("settings","Permission Denial late stderr",7);
    shellExecution("ime","",9);
    boolean commandRejected=false;
    try {FixtureDumpProjection.shellScript("dumpsys -t 2 settings; echo unsafe");} catch(IllegalArgumentException expected){commandRejected=true;}
    check(commandRejected,"arbitrary shell command accepted");count++;
    System.out.println("PASS "+count+" actual Java projection host cases; no Android runtime claim");
  }
}
'''
with tempfile.TemporaryDirectory(prefix='rdpsync-projection-host-') as temp:
    t=Path(temp);h=t/'ProjectionHostTest.java';h.write_text(HARNESS)
    subprocess.run([javac,'-encoding','UTF-8','-d',str(t),str(source.resolve()),str(h)],check=True,timeout=60)
    subprocess.run([java,'-cp',str(t),'ProjectionHostTest'],check=True,timeout=60)
