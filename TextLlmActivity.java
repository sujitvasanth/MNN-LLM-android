package com.handy.mnnchat;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.database.Cursor;
import android.view.View;
import android.view.WindowManager;
import android.view.Window;
import android.widget.*;
import android.app.DownloadManager;
import android.os.Environment;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.database.Cursor;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** MNN variant of the TextLLM v7 chat screen. */
public final class TextLlmActivity extends Activity {
  private final ExecutorService worker=Executors.newSingleThreadExecutor();
  private TextView modelLabel,status,output; private EditText prompt,context,max;
  private Spinner backend,kvCache,modelChoice; private EditText customModelUrl; private TextView kvNote; private View setup; private Button pick,load,send,clear,stop,download;
  private ProgressBar downloadProgress; private TextView downloadStatus;
  private Switch thinking,mmap;
  private boolean busy=false,loaded=false; private volatile boolean destroyed=false;
  private File modelDir; private String transcript="",prefix=""; private ScrollView chat;
  private String rawDisplay="";
  private final java.util.Set<Integer> expandedThinking=new java.util.HashSet<>();
  private String nativeError;
  private TextView diagnostics;
  private static final String HF_BASE="https://huggingface.co/";
  private static final String[][] REMOTE_MODELS={{"FuseChat Llama 3.2 3B","sujitvasanth/FuseChat-MNNLlama3.2-3B-MNN"},{"DeepSeek R1 Distill Llama 3B","sujitvasanth/suayptalha-DeepSeek-R1-Distill-Llama-3B"}};
  private final String[] REMOTE_FILES={"config.json","export_args.json","llm_config.json","llm.mnn","llm.mnn.json","llm.mnn.weight","tokenizer.mtok","tokenizer.txt","embeddings_int4.bin","visual.mnn","visual.mnn.weight"};
  public void onNativeDiagnostics(String message){ui(()->diagnostics.setText(message));}
  private native String loadNative(String config,String settings,int context);
  private native String generateNative(String text,int max);
  private native void cancelNative(); private native void closeNative(); private native void resetNative();
  @Override public void onCreate(Bundle b) {
    super.onCreate(b);
    getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    if(android.os.Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(true);
    try {System.loadLibrary("mnn_bridge");} catch(Throwable t){nativeError=t.toString();}
    LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(20,16,20,24);
    TextView title=new TextView(this);title.setText("TextLLM · MNN · cache diagnostic 2");title.setTextSize(22);root.addView(title);
    root.setOnApplyWindowInsetsListener((v,insets)->{int nav=0,ime=0;if(android.os.Build.VERSION.SDK_INT>=30){nav=insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom;ime=insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;}else{nav=insets.getSystemWindowInsetBottom();}v.setPadding(v.getPaddingLeft(),v.getPaddingTop(),v.getPaddingRight(),Math.max(24,Math.max(nav,ime)));return insets;});
    ScrollView setupScroll=new ScrollView(this); LinearLayout controls=new LinearLayout(this);controls.setOrientation(1);setupScroll.addView(controls);setup=setupScroll;root.addView(setupScroll);
    pick=button("CHOOSE MNN MODEL FOLDER",controls);modelLabel=new TextView(this);controls.addView(modelLabel);
    modelChoice=new Spinner(this);modelChoice.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{REMOTE_MODELS[0][0],REMOTE_MODELS[1][0]}));controls.addView(modelChoice);
    customModelUrl=field("Custom Hugging Face model URL (optional)",getPreferences(0).getString("customModelUrl",""),controls);
    download=button("DOWNLOAD SELECTED MODEL",controls);downloadProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);downloadProgress.setMax(100);downloadProgress.setVisibility(View.GONE);controls.addView(downloadProgress);downloadStatus=new TextView(this);controls.addView(downloadStatus);
    backend=new Spinner(this);backend.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"CPU","OpenCL GPU"}));controls.addView(backend);
    TextView kvLabel=new TextView(this);kvLabel.setText("KV cache quantisation (applied when loading)");controls.addView(kvLabel);
    kvCache=new Spinner(this);kvCache.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"FP16 - default","INT8 keys","INT8 keys + values","TurboQuant TQ3 keys","TurboQuant TQ3 keys + values","TurboQuant TQ4 keys","TurboQuant TQ4 keys + values"}));controls.addView(kvCache);
    kvCache.setSelection(getPreferences(0).getInt("kvMode",0));
    kvNote=new TextView(this);controls.addView(kvNote);
    thinking=new Switch(this);thinking.setText("Thinking (supported models; reload to apply)");thinking.setChecked(getPreferences(0).getBoolean("thinking",true));controls.addView(thinking);
    mmap=new Switch(this);mmap.setText("Memory-map model weights (reload to apply)");mmap.setChecked(getPreferences(0).getBoolean("mmap",false));controls.addView(mmap);
    backend.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int position,long id){updateKvChoice();}public void onNothingSelected(AdapterView<?> p){}});
    context=field("Context tokens","4096",controls);max=field("Maximum reply tokens","256",controls);
    context.setInputType(2);max.setInputType(2);load=button("LOAD MODEL",controls);
    status=new TextView(this);root.addView(status);
    diagnostics=new TextView(this);diagnostics.setTextSize(12);root.addView(diagnostics);
    LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(android.view.Gravity.TOP);root.addView(row,new LinearLayout.LayoutParams(-1,-2));
    prompt=new EditText(this);prompt.setHint("Message");prompt.setSingleLine(false);prompt.setMinLines(2);prompt.setMaxLines(2);prompt.setGravity(android.view.Gravity.TOP|android.view.Gravity.START);prompt.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);row.addView(prompt,new LinearLayout.LayoutParams(0,-2,1));
    LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.VERTICAL);int actionWidth=(int)(88*getResources().getDisplayMetrics().density);row.addView(actions,new LinearLayout.LayoutParams(actionWidth,-2));
    send=button("SEND",actions);Button back=button("SETUP",actions);stop=button("STOP",actions);
    chat=new ScrollView(this);output=new TextView(this);output.setTextSize(16);output.setTextIsSelectable(true);output.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());chat.addView(output);root.addView(chat,new LinearLayout.LayoutParams(-1,0,1));
    clear=button("NEW CHAT",controls);setContentView(root);
    modelDir=new File(getFilesDir(),getPreferences(0).getString("modelDir","model"));refreshModel();
    status.setText(nativeError==null?"Choose the folder containing the seven MNN model files, then Load.":"Native library error: "+nativeError);
    pick.setOnClickListener(v->{try{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,7);}catch(Exception e){status.setText("Cannot open folder picker: "+e.getMessage());}});
    download.setOnClickListener(v->downloadSelectedModel());
    load.setOnClickListener(v->loadModel());send.setOnClickListener(v->generate());stop.setOnClickListener(v->{cancelNative();status.setText("Stopping after the current model step…");});
    back.setOnClickListener(v->setup.setVisibility(setup.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));
    clear.setOnClickListener(v->{if(busy)return;setBusy(true);worker.execute(()->{resetNative();ui(()->{transcript="";output.setText("");status.setText("New chat.");setBusy(false);});});});
    setBusy(false);
  }
  private Button button(String label,LinearLayout parent){Button b=new Button(this);b.setText(label);parent.addView(b,parent.getOrientation()==0?new LinearLayout.LayoutParams(0,-2,1):new LinearLayout.LayoutParams(-1,-2));return b;}
  private EditText field(String hint,String value,LinearLayout parent){EditText e=new EditText(this);e.setHint(hint);e.setText(value);parent.addView(e);return e;}
  private void updateKvChoice(){boolean cpu=backend.getSelectedItemPosition()==0;kvCache.setEnabled(!busy&&cpu);kvNote.setText(cpu?"Quantised modes are experimental. Reload the model after changing.":"OpenCL uses its native KV cache; these quantisation options apply to CPU only.");}
  private void ui(Runnable r){runOnUiThread(()->{if(!destroyed)r.run();});}
  private void setBusy(boolean value){busy=value;pick.setEnabled(!busy);download.setEnabled(!busy);modelChoice.setEnabled(!busy);load.setEnabled(!busy&&nativeError==null);send.setEnabled(!busy&&loaded);clear.setEnabled(!busy&&loaded);stop.setEnabled(busy&&loaded);backend.setEnabled(!busy);updateKvChoice();context.setEnabled(!busy);max.setEnabled(!busy);if(busy)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
  private void refreshModel(){modelLabel.setText(new File(modelDir,"config.json").isFile()?"Saved model ready: "+modelDir.getName():"No model imported");}
  private void downloadSelectedModel(){if(busy)return;final int which=modelChoice.getSelectedItemPosition();final String custom=customModelUrl.getText().toString().trim();worker.execute(()->{try{
    final String[] files={"config.json","export_args.json","llm_config.json","llm.mnn","llm.mnn.json","llm.mnn.weight","tokenizer.mtok"};
    String repo=custom.isEmpty()?REMOTE_MODELS[which][1]:custom.replaceAll("/$","").replaceFirst("https://huggingface.co/","");if(repo.contains("/tree/"))repo=repo.substring(0,repo.indexOf("/tree/"));if(!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))throw new IOException("Enter a Hugging Face URL like https://huggingface.co/user/model");
    getPreferences(0).edit().putString("customModelUrl",custom).apply();DownloadManager dm=(DownloadManager)getSystemService(DOWNLOAD_SERVICE);String folderName=custom.isEmpty()?REMOTE_MODELS[which][0].replace(' ','_'):repo.substring(repo.indexOf('/')+1);File dl=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"TextLLM Models/"+folderName);if(!dl.exists()&&!dl.mkdirs())throw new IOException("Cannot create Downloads folder");
    java.util.LinkedHashMap<Long,String> ids=new java.util.LinkedHashMap<>();long total=0;
    for(String name:files){File out=new File(dl,name);if(out.isFile()&&out.length()>0)continue;DownloadManager.Request req=new DownloadManager.Request(Uri.parse(HF_BASE+repo+"/resolve/main/"+name));req.setTitle(REMOTE_MODELS[which][0]);req.setDescription(name);req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);req.setDestinationUri(Uri.fromFile(out));ids.put(dm.enqueue(req),name);}
    ui(()->{downloadProgress.setVisibility(View.VISIBLE);downloadProgress.setProgress(0);downloadStatus.setText("Downloading "+REMOTE_MODELS[which][0]+"…");});
    boolean done=false;while(!done){done=true;long got=0,bytes=0;for(long id:ids.keySet()){try(Cursor c=dm.query(new DownloadManager.Query().setFilterById(id))){if(c!=null&&c.moveToFirst()){int st=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));long g=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));long t=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));got+=g;if(t>0)bytes+=t;if(st==DownloadManager.STATUS_PENDING||st==DownloadManager.STATUS_RUNNING||st==DownloadManager.STATUS_PAUSED)done=false;if(st==DownloadManager.STATUS_FAILED)throw new IOException("Download failed for model file");}}}
      final int p=bytes>0?(int)Math.min(100,(got*100)/bytes):0;ui(()->downloadProgress.setProgress(p));Thread.sleep(500);
    }
    File staging=new File(getFilesDir(),"download-"+System.currentTimeMillis());staging.mkdirs();for(String name:files){File src=new File(dl,name);if(!src.isFile()||src.length()==0)throw new IOException("Downloaded file missing: "+name);copyFile(src,new File(staging,name));}validate(staging);closeNative();File previous=modelDir;modelDir=staging;getPreferences(0).edit().putString("modelDir",getFilesDir().toPath().relativize(staging.toPath()).toString()).putBoolean("downloaded"+which,true).apply();if(previous.exists()&&!previous.equals(staging))deleteTree(previous);ui(()->{refreshModel();downloadStatus.setText("Downloaded and ready. Press LOAD MODEL.");downloadProgress.setProgress(100);});
  }catch(Exception e){ui(()->downloadStatus.setText("Download failed: "+e.getMessage()));} });}
  private void copyFile(File a,File b)throws IOException{try(InputStream in=new BufferedInputStream(new FileInputStream(a));OutputStream out=new BufferedOutputStream(new FileOutputStream(b))){byte[] x=new byte[262144];int n;while((n=in.read(x))!=-1)out.write(x,0,n);}}
  @Override protected void onActivityResult(int r,int c,Intent data){super.onActivityResult(r,c,data);if(r!=7||c!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();try{getContentResolver().takePersistableUriPermission(uri,data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION));}catch(Exception ignored){}setBusy(true);status.setText("Importing model files…");worker.execute(()->{
    File staging=new File(getFilesDir(),"import-"+System.currentTimeMillis());staging.mkdirs();
    try {
      copyFolderFiles(uri,staging);
      File found=findModel(staging);if(found==null)throw new IOException("Selected folder must contain config.json and the model's weight and tokenizer files.");
      validate(found);closeNative();loaded=false;
      File previous=modelDir;modelDir=found;
      String relative=getFilesDir().toPath().relativize(found.toPath()).toString();getPreferences(0).edit().putString("modelDir",relative).commit();
      if(previous.exists()&&!previous.equals(found))deleteTree(previous);
      ui(()->{transcript="";output.setText("");refreshModel();status.setText("Import complete. Press LOAD MODEL.");});
    }catch(Exception e){deleteTree(staging);ui(()->status.setText("Import failed: "+e.getMessage()));}finally{ui(()->setBusy(false));}
  });}
  private void copyFolderFiles(Uri tree,File staging)throws Exception{
    String docId=DocumentsContract.getTreeDocumentId(tree);Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,docId);String[] names={"config.json","export_args.json","llm_config.json","llm.mnn","llm.mnn.json","llm.mnn.weight","tokenizer.mtok","tokenizer.txt","embeddings_int4.bin","visual.mnn","visual.mnn.weight"};byte[] buf=new byte[262144];
    try(Cursor cur=getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)) {
      if(cur==null)throw new IOException("Cannot read selected folder");
      while(cur.moveToNext()) {
        String id=cur.getString(0),name=cur.getString(1);
        for(String wanted:names) if(wanted.equals(name)) {
          Uri fileUri=DocumentsContract.buildDocumentUriUsingTree(tree,id);File target=new File(staging,name);long total=0,last=0;
          try(InputStream in=getContentResolver().openInputStream(fileUri);OutputStream out=new BufferedOutputStream(new FileOutputStream(target))) {
            int n;while((n=in.read(buf))!=-1){if(destroyed)throw new IOException("Import cancelled");total+=n;out.write(buf,0,n);long now=System.currentTimeMillis();if(now-last>500){last=now;final long mb=total/1048576;ui(()->status.setText("Importing "+name+": "+mb+" MB…"));}}
          }
        }
      }
    }
  }
  private File findModel(File dir){if(new File(dir,"config.json").isFile())return dir;File[] children=dir.listFiles();if(children!=null)for(File f:children)if(f.isDirectory()){File result=findModel(f);if(result!=null)return result;}return null;}
  private void validate(File dir)throws Exception{
    for(String name:new String[]{"config.json","llm_config.json","llm.mnn","llm.mnn.weight"})if(!new File(dir,name).isFile()||new File(dir,name).length()==0)throw new IOException("Missing "+name);
    JSONObject cfg=new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(dir,"config.json").toPath()),StandardCharsets.UTF_8));
    String tokenizerName=cfg.optString("tokenizer_file","tokenizer.txt");
    File tokenizer=new File(dir,tokenizerName);
    if(!tokenizer.getCanonicalPath().startsWith(dir.getCanonicalPath()+File.separator)||!tokenizer.isFile()||tokenizer.length()==0)throw new IOException("Missing or invalid tokenizer: "+tokenizerName);
    JSONObject info=new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(dir,cfg.optString("llm_config","llm_config.json")).toPath()),StandardCharsets.UTF_8));
    if(info.optBoolean("is_visual",false)){
      String visualName=cfg.optString("visual_model","visual.mnn");
      File visual=new File(dir,visualName);
      if(!visual.getCanonicalPath().startsWith(dir.getCanonicalPath()+File.separator)||!visual.isFile()||visual.length()==0)throw new IOException("Missing visual model: "+visualName);
      File visualWeight=new File(dir,"visual.mnn.weight");
      if(!visualWeight.isFile()||visualWeight.length()==0)throw new IOException("Missing visual model weights: visual.mnn.weight");
    }
    Object tied=info.opt("tie_embeddings");
    long weightOffset=0,alphaOffset=0,alphaSize=0;
    if(tied instanceof org.json.JSONArray){org.json.JSONArray a=(org.json.JSONArray)tied;if(a.length()>=5){weightOffset=a.getLong(0);alphaOffset=a.getLong(1);alphaSize=a.getLong(2);}}
    else if(tied instanceof JSONObject){JSONObject a=(JSONObject)tied;weightOffset=a.optLong("weight_offset",0);alphaOffset=a.optLong("alpha_offset",0);alphaSize=a.optLong("alpha_size",0);}
    if(alphaOffset>0&&alphaSize>0){File weights=new File(dir,weightOffset==0?cfg.optString("embedding_file","embeddings_bf16.bin"):cfg.optString("llm_weight","llm.mnn.weight"));long required=Math.addExact(alphaOffset,alphaSize);if(weights.length()<required)throw new IOException("Incomplete "+weights.getName()+": "+weights.length()+" bytes; embeddings require at least "+required+" bytes. Finish downloading and re-import the folder.");}
    for(String key:new String[]{"llm_model","llm_weight","tokenizer_file","llm_config","embedding_file"})if(cfg.has(key)){File file=new File(dir,cfg.getString(key));if(!file.getCanonicalPath().startsWith(dir.getCanonicalPath()+File.separator)||!file.isFile())throw new IOException("Invalid or missing model file: "+key);}
  }
  private void deleteTree(File f){if(f.isDirectory()){File[] children=f.listFiles();if(children!=null)for(File c:children)deleteTree(c);}f.delete();}
  private int number(EditText f,int fallback){try{return Integer.parseInt(f.getText().toString().trim());}catch(Exception e){return fallback;}}
  private void loadModel(){if(busy)return;if(!new File(modelDir,"config.json").isFile()){status.setText("Choose the converted MNN model folder first.");return;}final int ctx=number(context,4096);if(ctx<512||ctx>8192){status.setText("Choose 512–8192 context tokens.");return;}
    final String engine=backend.getSelectedItemPosition()==1?"opencl":"cpu";final int kvMode=kvCache.getSelectedItemPosition();final boolean thinkEnabled=thinking.isChecked();final boolean useMmap=mmap.isChecked();getPreferences(0).edit().putInt("kvMode",kvMode).putBoolean("thinking",thinkEnabled).putBoolean("mmap",useMmap).apply();loaded=false;setBusy(true);status.setText("Loading model with "+engine+"…");worker.execute(()->{try{
      JSONObject cfg=new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(modelDir,"config.json").toPath()),StandardCharsets.UTF_8));
      cfg.put("backend_type",engine);cfg.put("thread_num",4);cfg.put("use_mmap",useMmap);cfg.put("use_cached_mmap",useMmap);cfg.put("reuse_kv",true);cfg.put("prompt_cache",true);cfg.put("max_all_tokens",ctx);
      cfg.put("async",false);
      JSONObject modelInfo=new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(modelDir,cfg.optString("llm_config","llm_config.json")).toPath()),StandardCharsets.UTF_8));
      if(modelInfo.optBoolean("is_visual",false)){
        cfg.put("is_visual",true).put("visual_model","visual.mnn").put("visual_weight","visual.mnn.weight");
        JSONObject mllm=cfg.optJSONObject("mllm");if(mllm==null)mllm=new JSONObject();mllm.put("backend_type",engine).put("thread_num",4);cfg.put("mllm",mllm);
      }
      JSONObject jinja=modelInfo.optJSONObject("jinja");if(jinja==null)jinja=cfg.optJSONObject("jinja");if(jinja==null)jinja=new JSONObject();
      if(!jinja.has("chat_template")&&modelInfo.has("user_prompt_template")&&modelInfo.has("assistant_prompt_template")){
        String assistantTemplate=modelInfo.getString("assistant_prompt_template");
        int marker=assistantTemplate.indexOf("%s");if(marker<0)throw new IOException("Invalid legacy assistant prompt template");
        StringBuilder legacy=new StringBuilder("{{ "+JSONObject.quote(modelInfo.optString("bos",""))+" }}{% for message in messages %}");
        for(String role:new String[]{"system","user","assistant"}){
          String value=modelInfo.optString(role+"_prompt_template","");int split=value.indexOf("%s");
          if(split>=0)legacy.append("{% if message.role == ").append(JSONObject.quote(role)).append(" %}{{ ").append(JSONObject.quote(value.substring(0,split))).append(" + message.content + ").append(JSONObject.quote(value.substring(split+2))).append(" }}{% endif %}");
        }
        legacy.append("{% endfor %}{% if add_generation_prompt %}{{ ").append(JSONObject.quote(assistantTemplate.substring(0,marker))).append(" }}{% endif %}");
        jinja.put("chat_template",legacy.toString());
      }
      JSONObject templateContext=jinja.optJSONObject("context");if(templateContext==null)templateContext=new JSONObject();templateContext.put("enable_thinking",thinkEnabled);
      jinja.put("context",templateContext);cfg.put("jinja",jinja);
      String modelCacheId=java.util.UUID.nameUUIDFromBytes(modelDir.getCanonicalPath().getBytes(StandardCharsets.UTF_8)).toString();
      File weightCache=new File(getCacheDir(),"mnn-weights-v2/"+modelCacheId+"/"+engine+"-kv"+kvMode);
      if(!weightCache.isDirectory()&&!weightCache.mkdirs())throw new IOException("Cannot create model weight cache directory");
      cfg.put("tmp_path",weightCache.getAbsolutePath()+"/");
      cfg.put("attention_mode",engine.equals("cpu")?8+kvMode:8);
      validate(modelDir);
      String error=loadNative(new File(modelDir,"config.json").getAbsolutePath(),cfg.toString(),ctx);ui(()->{loaded=error==null;if(loaded){transcript="";output.setText("");setup.setVisibility(View.GONE);status.setText("Ready · "+engine+" · "+ctx+" context");}else status.setText(error);});
    }catch(Throwable t){ui(()->status.setText("Load failed: "+t));}finally{ui(()->setBusy(false));}});
  }
  private void generate(){if(busy||!loaded)return;final String message=prompt.getText().toString().trim();if(message.isEmpty())return;final int n=number(max,256);if(n<1||n>Math.min(2048,number(context,4096)/2)){status.setText("Reply tokens must be 1–2048 and at most half the context.");return;}if(transcript.isEmpty())expandedThinking.clear();prefix=transcript+"\nYou: "+message+"\nAssistant: ";showChat(prefix);prompt.setText("");setBusy(true);status.setText("Generating…");final long start=System.nanoTime();worker.execute(()->{try{String error=generateNative(message,n);ui(()->{if(error!=null){showChat(rawDisplay+"\n["+error+"]");status.setText(error);}else status.setText(String.format(java.util.Locale.ROOT,"Finished in %.1f seconds",(System.nanoTime()-start)/1e9));transcript=rawDisplay+"\n";});}catch(Throwable t){ui(()->status.setText("Generation failed: "+t));}finally{ui(()->setBusy(false));}});}
  private void showChat(String text){
    rawDisplay=text;
    android.text.SpannableStringBuilder view=new android.text.SpannableStringBuilder();
    int pos=0;
    while(pos<text.length()){
      int open=text.indexOf("<think>",pos);
      if(open<0){view.append(text.substring(pos));break;}
      view.append(text.substring(pos,open));
      int close=text.indexOf("</think>",open+7);
      final int key=open;
      boolean expanded=expandedThinking.contains(key);
      int labelStart=view.length();
      view.append(expanded?"[-] Thinking":"[+] Thinking");
      if(close<0)view.append(" (in progress)");
      view.setSpan(new android.text.style.ClickableSpan(){@Override public void onClick(View v){if(!expandedThinking.remove(key))expandedThinking.add(key);showChat(rawDisplay);}},labelStart,view.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      view.append("\n");
      if(expanded){String thought=text.substring(open+7,close<0?text.length():close).trim();view.append(thought).append("\n");}
      if(close<0)break;
      pos=close+8;
    }
    output.setText(view);
  }
  public void onNativeText(byte[] bytes){final String text=new String(bytes,StandardCharsets.UTF_8);ui(()->{showChat(prefix+text);chat.post(()->chat.fullScroll(View.FOCUS_DOWN));});}
  @Override protected void onDestroy(){destroyed=true;if(nativeError==null){cancelNative();worker.execute(()->closeNative());}worker.shutdown();super.onDestroy();}
}
