import http from "node:http";
import crypto from "node:crypto";

const PORT = Number(process.env.PORT || 3000);
const key = process.env.OPENAI_API_KEY || "";
const token = process.env.TRACKER_CLIENT_TOKEN || "";
const model = process.env.OPENAI_MODEL || "gpt-4o-mini";
const categories = new Set(["Food","Transportation","Bills","Shopping","Health","Other"]);
const buckets = new Map();

function json(res,status,body){
  res.writeHead(status,{"Content-Type":"application/json; charset=utf-8","Cache-Control":"no-store","X-Content-Type-Options":"nosniff"});
  res.end(JSON.stringify(body));
}
async function readJSON(req){
  let size=0;const chunks=[];
  for await(const chunk of req){
    size+=chunk.length;
    if(size>16_384)throw Error("Request exceeds 16 KB");
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}
function constantTimeEqual(a,b){
  const aa=Buffer.from(a);const bb=Buffer.from(b);
  return aa.length===bb.length && crypto.timingSafeEqual(aa,bb);
}
function allowRequest(ip){
  const now=Date.now(), b=buckets.get(ip)||{time:now,count:0};
  if(now-b.time>60_000){b.time=now;b.count=0}
  b.count++;buckets.set(ip,b);
  return b.count<=25;
}
const server=http.createServer(async(req,res)=>{
  try {
    if(req.method==="GET" && req.url==="/healthz")return json(res,200,{status:"ok",aiConfigured:Boolean(key&&token)});
    if(req.method!=="POST" || req.url!=="/interpret")return json(res,404,{error:"Not found"});
    if(!key || !token)return json(res,503,{error:"AI backend not configured; set OPENAI_API_KEY and TRACKER_CLIENT_TOKEN."});
    if(!constantTimeEqual(req.headers["x-tracker-token"]||"",token))return json(res,401,{error:"Unauthorized"});
    if(!allowRequest(req.socket.remoteAddress||"unknown"))return json(res,429,{error:"Too many requests"});
    const body=await readJSON(req);
    if(typeof body.text!=="string" || !body.text.trim() || body.text.length>600)return json(res,400,{error:"A short spoken-expense transcript is required"});
    const controller=new AbortController();
    const timer=setTimeout(()=>controller.abort(),15_000);
    let ai;
    try {
      ai=await fetch("https://api.openai.com/v1/chat/completions",{
        method:"POST",signal:controller.signal,
        headers:{"Authorization":"Bearer "+key,"Content-Type":"application/json"},
        body:JSON.stringify({
          model,temperature:0,response_format:{type:"json_object"},
          messages:[
            {role:"system",content:"You extract one expense from a single utterance in English, Filipino, or Taglish. Reply ONLY JSON with keys action, amount, category, item. action must be expense or none. amount is whole Philippine pesos integer 1..1000000 or 0 when none; do not invent amounts. category must be exactly one of Food, Transportation, Bills, Shopping, Health, Other. item must be concise, recognizable, max 80 characters. If speaker is asking budget/history or if amount is ambiguous, action none."},
            {role:"user",content:body.text}
          ]
        })
      });
    }finally {clearTimeout(timer)}
    if(!ai.ok)return json(res,502,{error:"AI provider request unsuccessful"});
    const result=await ai.json();
    const parsed=JSON.parse(result.choices?.[0]?.message?.content||"{}");
    const action=parsed.action==="expense"?"expense":"none";
    const amount=Number.isSafeInteger(parsed.amount)?parsed.amount:0;
    if(action==="none" || amount<1 || amount>1000000)return json(res,200,{action:"none",amount:0,category:"Other",item:""});
    const category=categories.has(parsed.category)?parsed.category:"Other";
    const item=typeof parsed.item==="string"?parsed.item.trim().slice(0,80):category;
    return json(res,200,{action:"expense",amount,category,item:item||category});
  }catch(err){
    return json(res,500,{error:"Request could not be processed"});
  }
});
server.listen(PORT,"0.0.0.0");
