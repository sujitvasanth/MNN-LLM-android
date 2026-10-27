#include <jni.h>
#include <llm/llm.hpp>
#include "llmconfig.hpp"
#include <atomic>
#include <memory>
#include <streambuf>
#include <algorithm>
#include <sstream>
using namespace MNN::Transformer;
struct State {
    std::unique_ptr<Llm> llm;
    ChatMessages history;
    std::atomic<bool> cancel{false};
    int limit = 4096;
};
static State& state() { static State s; return s; }
static std::string utf8(JNIEnv* e, jstring s) {
    jclass cls = e->FindClass("java/lang/String");
    auto method = e->GetMethodID(cls, "getBytes", "(Ljava/lang/String;)[B");
    jstring charset = e->NewStringUTF("UTF-8");
    auto bytes = (jbyteArray)e->CallObjectMethod(s, method, charset);
    std::string result(e->GetArrayLength(bytes), '\0');
    e->GetByteArrayRegion(bytes, 0, result.size(), reinterpret_cast<jbyte*>(&result[0]));
    e->DeleteLocalRef(bytes); e->DeleteLocalRef(charset); e->DeleteLocalRef(cls);
    return result;
}
struct Stream : std::streambuf {
    JNIEnv* env; jobject target; jmethodID callback; std::string text;
    bool pendingEnd = false;
    Stream(JNIEnv* e, jobject o) : env(e), target(o) {
        auto cls=e->GetObjectClass(o); callback=e->GetMethodID(cls,"onNativeText","([B)V"); e->DeleteLocalRef(cls);
    }
    std::streamsize xsputn(const char* p, std::streamsize n) override {
        std::string chunk(p,n);
        if(chunk == "<eop>") pendingEnd = true;
        else text.append(p,n);
        return n;
    }
    int overflow(int c) override { if(c!=traits_type::eof()) text.push_back((char)c); return traits_type::not_eof(c); }
    int sync() override {
        auto b=env->NewByteArray(text.size());
        env->SetByteArrayRegion(b,0,text.size(),reinterpret_cast<const jbyte*>(text.data()));
        env->CallVoidMethod(target,callback,b); env->DeleteLocalRef(b);
        if(state().cancel.load()) const_cast<LlmContext*>(state().llm->getContext())->status=LlmStatus::USER_CANCEL;
        return 0;
    }
};
extern "C" JNIEXPORT jstring JNICALL Java_com_handy_mnnchat_TextLlmActivity_loadNative(JNIEnv* e,jobject o,jstring path,jstring settings,jint limit) {
    auto& s=state(); s.llm.reset(); s.history.clear(); s.limit=limit;
    const std::string modelPath=utf8(e,path);
    auto modelConfig=std::make_shared<LlmConfig>(modelPath);
    const bool visualModel=modelConfig->is_visual();
    if(visualModel){modelConfig->config_["is_visual"]=false;modelConfig->mllm_config_=ujson::json();s.llm.reset(new Llm(modelConfig));}
    else s.llm.reset(Llm::createLLM(modelPath));
    if(!s.llm) return e->NewStringUTF("MNN could not create the model.");
    s.llm->set_config(utf8(e,settings));
    if(!s.llm->load()) {
        std::string log=s.llm->getLog();
        s.llm.reset();
        if(log.size()>900) log=log.substr(log.size()-900);
        std::string message="MNN model loading failed: "+log;
        return e->NewStringUTF(message.c_str());
    }
    const std::string probe="Hello world!";
    const auto ids=s.llm->tokenizer_encode(probe);
    std::string decoded;
    for(int id:ids) decoded+=s.llm->tokenizer_decode(id);
    const bool roundTrip=decoded.find(probe)!=std::string::npos;
    auto cls=e->GetObjectClass(o);auto method=e->GetMethodID(cls,"onNativeDiagnostics","(Ljava/lang/String;)V");
    auto message=e->NewStringUTF(roundTrip?"Tokenizer text round-trip: PASS":"Tokenizer text round-trip: FAILED");
    e->CallVoidMethod(o,method,message);e->DeleteLocalRef(message);e->DeleteLocalRef(cls);
    if(!roundTrip){s.llm.reset();return e->NewStringUTF("Tokenizer failed the Hello world! round-trip test. Check tokenizer file compatibility.");}
    return nullptr;
}
extern "C" JNIEXPORT jstring JNICALL Java_com_handy_mnnchat_TextLlmActivity_generateNative(JNIEnv* e,jobject o,jstring prompt,jint maxTokens) {
    auto& s=state(); if(!s.llm) return e->NewStringUTF("Load a model first.");
    s.cancel=false;
    auto next=s.history; next.emplace_back("user",utf8(e,prompt));
    std::string plain;
    for(const auto& message:next)plain+=message.second;
    if(s.llm->apply_chat_template(next)==plain)
        return e->NewStringUTF("No active chat template: model received raw text. Re-import the model's llm_config.json with its chat template.");
    int budget=s.limit-maxTokens;
    while(s.llm->tokenizer_encode(s.llm->apply_chat_template(next)).size()>(size_t)budget) {
        if(next.size()<=1) return e->NewStringUTF("Message is too long for the selected context. Shorten it or increase context.");
        next.erase(next.begin(),next.begin()+2);
    }
    auto* before=s.llm->getContext();
    const int cachedBefore=before->all_seq_len;
    const int fullPrompt=s.llm->tokenizer_encode(s.llm->apply_chat_template(next)).size();
    const_cast<LlmContext*>(before)->status=LlmStatus::RUNNING;
    Stream buf(e,o); std::ostream output(&buf);
    // Follow MnnLlmChat/llm_session.cpp: prefill first, then step decode.
    s.llm->response(next,&output,"<eop>",0);
    auto* ctx=const_cast<LlmContext*>(s.llm->getContext());
    for(int step=0;step<maxTokens && !s.cancel.load();++step) {
        if(ctx->status==LlmStatus::INTERNAL_ERROR || ctx->status==LlmStatus::TIMEOUT || ctx->status==LlmStatus::USER_CANCEL) break;
        if(ctx->status==LlmStatus::NORMAL_FINISHED && buf.pendingEnd) break;
        if(ctx->status==LlmStatus::NORMAL_FINISHED || ctx->status==LlmStatus::MAX_TOKENS_FINISHED) ctx->status=LlmStatus::RUNNING;
        buf.pendingEnd=false;
        s.llm->generate(1);
        output.flush();
        if(ctx->status==LlmStatus::NORMAL_FINISHED && buf.pendingEnd) break;
    }
    auto status=s.llm->getContext()->status;
    std::ostringstream details;
    details << "messages=" << next.size() << " full=" << fullPrompt
            << " cached-before=" << cachedBefore << " prefill=" << ctx->prompt_len
            << " generated=" << ctx->gen_seq_len << " cached-after=" << ctx->all_seq_len
            << " status=" << static_cast<int>(status) << " last-token=" << ctx->current_token;
    auto diagClass=e->GetObjectClass(o);
    auto diagMethod=e->GetMethodID(diagClass,"onNativeDiagnostics","(Ljava/lang/String;)V");
    auto diagText=e->NewStringUTF(details.str().c_str());
    e->CallVoidMethod(o,diagMethod,diagText);
    e->DeleteLocalRef(diagText);e->DeleteLocalRef(diagClass);
    // NORMAL_FINISHED and MAX_TOKENS_FINISHED are successful turns. TIMEOUT
    // can also leave a usable partial response (for example a prefill/decode
    // budget expiry); keep the generated text so the next turn can continue.
    if(status==LlmStatus::INTERNAL_ERROR) {
        return e->NewStringUTF("MNN internal error. See token/cache diagnostics above.");
    }
    const std::string assistant=buf.text;
    auto bytes=e->NewByteArray(assistant.size());
    e->SetByteArrayRegion(bytes,0,assistant.size(),reinterpret_cast<const jbyte*>(assistant.data()));
    auto cls=e->GetObjectClass(o);auto cb=e->GetMethodID(cls,"onNativeText","([B)V");e->CallVoidMethod(o,cb,bytes);e->DeleteLocalRef(cls);e->DeleteLocalRef(bytes);
    next.emplace_back("assistant",assistant); s.history=std::move(next);
    // The Java side stores the exact generated text in history. Tell MNN to
    // render that same post-processed conversation into its prompt-cache text
    // before the next ChatMessages response.
    s.llm->syncPromptCache(s.history);
    if(status==LlmStatus::TIMEOUT) return e->NewStringUTF("MNN timed out; partial reply saved.");
    return nullptr;
}
extern "C" JNIEXPORT void JNICALL Java_com_handy_mnnchat_TextLlmActivity_cancelNative(JNIEnv*,jobject) { state().cancel=true; }
extern "C" JNIEXPORT void JNICALL Java_com_handy_mnnchat_TextLlmActivity_closeNative(JNIEnv*,jobject) { state().llm.reset(); state().history.clear(); }
extern "C" JNIEXPORT void JNICALL Java_com_handy_mnnchat_TextLlmActivity_resetNative(JNIEnv*,jobject) { auto& s=state(); if(s.llm) s.llm->reset(); s.history.clear(); }
