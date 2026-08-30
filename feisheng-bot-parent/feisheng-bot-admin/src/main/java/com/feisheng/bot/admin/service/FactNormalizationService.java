package com.feisheng.bot.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.knowledge.entity.BotKnowledgeSemanticUnit;
import org.springframework.stereotype.Service;
import java.math.*;
import java.text.Normalizer;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Converts extracted fact fields into deterministic, evidence-preserving values. */
@Service
public class FactNormalizationService {
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern NUMBER = Pattern.compile("(?<![\\d.])(-?(?:\\d+(?:\\.\\d+)?|[零〇一二两三四五六七八九十百千万亿]+))\\s*(万元|元|%|％|天|日|小时|小時|分钟|分鐘|分|秒|件|次|人)?");
    private static final Pattern DATE = Pattern.compile("(?<!\\d)(\\d{4})[年/-](\\d{1,2})[月/-](\\d{1,2})(?:日)?(?:[ T](\\d{1,2}):(\\d{2})(?::(\\d{2}))?(?:([+-]\\d{2}:?\\d{2})|Z)?)?");
    private static final Map<String,String> POLARITY = Map.ofEntries(Map.entry("不可以","FORBIDDEN"),Map.entry("不可","FORBIDDEN"),Map.entry("不允许","FORBIDDEN"),Map.entry("禁止","FORBIDDEN"),Map.entry("不支持","FORBIDDEN"),Map.entry("可以","ALLOWED"),Map.entry("允许","ALLOWED"),Map.entry("支持","ALLOWED"),Map.entry("必须","REQUIRED"),Map.entry("需要","REQUIRED"),Map.entry("无需","NOT_REQUIRED"),Map.entry("不需要","NOT_REQUIRED"));
    private final ObjectMapper objectMapper;
    public FactNormalizationService(ObjectMapper objectMapper) { this.objectMapper = Objects.requireNonNull(objectMapper,"objectMapper"); }

    public NormalizedFact normalize(BotKnowledgeSemanticUnit unit) {
        Objects.requireNonNull(unit,"unit");
        String question=normalizeText(unit.getQuestion()), statement=normalizeText(unit.getStatement());
        String text=question+" "+statement;
        String extractionText=toHalfWidth((unit.getQuestion()==null?"":unit.getQuestion())+" "+(unit.getStatement()==null?"":unit.getStatement()));
        Map<String,BigDecimal> numbers=new LinkedHashMap<>(); Map<String,NumericRange> ranges=new LinkedHashMap<>(); extractNumbers(text,numbers,ranges);
        Map<String,String> temporal=new LinkedHashMap<>(); extractTemporal(extractionText,temporal); Set<String> enums=new LinkedHashSet<>();
        addJsonValues(unit.getEntitiesJson(),enums); addJsonValues(unit.getConditionsJson(),enums); addJsonValues(unit.getExclusionsJson(),enums);
        return new NormalizedFact(question,statement,detectPolarity(text),numbers,ranges,temporal,enums,processSteps(unit.getConditionsJson()),parseScope(unit),parseEvidence(unit.getEvidenceChunkIdsJson()),unit.getQuestion(),unit.getStatement());
    }
    private void extractNumbers(String text,Map<String,BigDecimal> numbers,Map<String,NumericRange> ranges) {
        String source=toHalfWidth(text); Matcher m=NUMBER.matcher(source); int i=0;
        while(m.find()){ if(isDatePart(source,m.start())) continue; BigDecimal value=parseNumber(m.group(1)); if(value==null) continue; String unit=Optional.ofNullable(m.group(2)).orElse(""); BigDecimal n=convertUnit(value,unit); String category=category(unit,source,m.start()); String key=category+"#"+i++; numbers.put(key,n); NumericRange r=rangeAround(source,m.start(),m.end(),n,category); if(r!=null) ranges.put(key,r); }
    }
    private boolean isDatePart(String text,int start){ String around=text.substring(Math.max(0,start-1),Math.min(text.length(),start+8)); return around.matches(".*\\d{4}[-/]\\d{1,2}.*")||around.contains("年")||around.contains("月"); }
    private NumericRange rangeAround(String text,int start,int end,BigDecimal value,String category){ String before=text.substring(Math.max(0,start-5),start); String after=text.substring(end,Math.min(text.length(),end+2));
        if(before.matches(".*(不超过|不高于|至多|小于等于|不大于)$")) return new NumericRange(null,value,true,true,category);
        if(before.matches(".*(不低于|不少于|至少|大于等于)$")) return new NumericRange(value,null,true,true,category);
        if(before.matches(".*(小于|少于|低于)$")) return new NumericRange(null,value,true,false,category);
        if(before.matches(".*(大于|超过|高于)$")) return new NumericRange(value,null,false,true,category);
        if(after.startsWith("-")||after.startsWith("~")||after.startsWith("至")) {
            Matcher upper = NUMBER.matcher(after.substring(1));
            if (upper.find() && upper.start() == 0) {
                BigDecimal upperValue = parseNumber(upper.group(1));
                if (upperValue != null) upperValue = convertUnit(upperValue, Optional.ofNullable(upper.group(2)).orElse(""));
                if (upperValue != null) return new NumericRange(value, upperValue, true, true, category);
            }
            return new NumericRange(value,null,true,true,category);
        }
        return null; }
    private String category(String unit,String text,int position){ return switch(unit){case "万元","元"->"amount";case "%","％"->"rate";case "天","日","小时","小時","分钟","分鐘","分","秒"->"durationDays";default->text.substring(Math.max(0,position-3),position).contains("比例")?"rate":"number";}; }
    private void extractTemporal(String text,Map<String,String> out){ Matcher m=DATE.matcher(toHalfWidth(text)); while(m.find()) try { int y=Integer.parseInt(m.group(1)),mo=Integer.parseInt(m.group(2)),d=Integer.parseInt(m.group(3)); String k="date#"+out.size(); if(m.group(4)==null) out.put(k,LocalDate.of(y,mo,d).atStartOfDay(DEFAULT_ZONE).toInstant().toString()); else { int h=Integer.parseInt(m.group(4)),mi=Integer.parseInt(m.group(5)),s=m.group(6)==null?0:Integer.parseInt(m.group(6)); String off=m.group(7); out.put(k,(off==null?LocalDateTime.of(y,mo,d,h,mi,s).atZone(DEFAULT_ZONE).toInstant():OffsetDateTime.of(y,mo,d,h,mi,s,0,ZoneOffset.of(off)).toInstant()).toString()); }} catch(DateTimeException ignored){} }
    private Scope parseScope(BotKnowledgeSemanticUnit unit){ Map<String,String> f=new LinkedHashMap<>(); collectObjectValues(read(unit.getMetadataJson()),f); collectObjectValues(read(unit.getConditionsJson()),f); return new Scope(f.isEmpty()?ScopeRelation.UNKNOWN:ScopeRelation.KNOWN,f); }
    private void collectObjectValues(JsonNode n,Map<String,String> out){ if(n==null||!n.isObject()) return; n.fields().forEachRemaining(e->{ if(e.getValue().isValueNode()&&!e.getValue().asText().isBlank()) out.putIfAbsent(e.getKey(),normalizeText(e.getValue().asText())); else if(e.getValue().isObject()) collectObjectValues(e.getValue(),out); }); }
    private List<String> processSteps(String json){ JsonNode n=read(json); if(n==null)return List.of(); List<String> r=new ArrayList<>(); if(n.isArray()) n.forEach(v->{if(v.isValueNode())r.add(normalizeText(v.asText()));}); else if(n.isObject()) n.elements().forEachRemaining(v->{if(v.isArray())v.forEach(x->{if(x.isValueNode())r.add(normalizeText(x.asText()));});}); return r; }
    private void addJsonValues(String json,Set<String> out){ addJsonValues(read(json),out); }
    private void addJsonValues(JsonNode n,Set<String> out){ if(n==null)return; if(n.isValueNode()){if(!n.asText().isBlank())out.add(normalizeText(n.asText()));} else n.elements().forEachRemaining(x->addJsonValues(x,out)); }
    private List<Long> parseEvidence(String json){ JsonNode n=read(json); if(n==null||!n.isArray())return List.of(); List<Long> r=new ArrayList<>(); n.forEach(x->{if(x.canConvertToLong())r.add(x.asLong());}); return List.copyOf(r); }
    private JsonNode read(String json){try{return json==null||json.isBlank()?null:objectMapper.readTree(json);}catch(Exception e){return null;}}
    private String detectPolarity(String text){ String[] keys={"不可以","不允许","不支持","禁止","不可","不需要","无需","必须","需要","可以","允许","支持"}; for(String k:keys)if(text.contains(k))return POLARITY.get(k); return "UNKNOWN"; }
    private BigDecimal parseNumber(String token){ try{return new BigDecimal(token);}catch(NumberFormatException e){return parseChineseNumber(token);} }
    private BigDecimal parseChineseNumber(String token){ Map<Character,Integer>d=Map.ofEntries(Map.entry('零',0),Map.entry('〇',0),Map.entry('一',1),Map.entry('二',2),Map.entry('两',2),Map.entry('三',3),Map.entry('四',4),Map.entry('五',5),Map.entry('六',6),Map.entry('七',7),Map.entry('八',8),Map.entry('九',9)); long total=0,section=0; int num=0; for(char c:token.toCharArray()){if(d.containsKey(c))num=d.get(c); else if(c=='十'||c=='百'||c=='千'){int u=c=='十'?10:c=='百'?100:1000; section+=(num==0?1:num)*u; num=0;} else if(c=='万'||c=='亿'){section+=num; total+=section*(c=='万'?10000:100000000L);section=0;num=0;} else return null;} return BigDecimal.valueOf(total+section+num); }
    private BigDecimal convertUnit(BigDecimal v,String u){ return switch(u){case "万元"->v.multiply(BigDecimal.valueOf(10000));case "%","％"->v.divide(BigDecimal.valueOf(100),8,RoundingMode.HALF_UP).stripTrailingZeros();case "小时","小時"->v.divide(BigDecimal.valueOf(24),8,RoundingMode.HALF_UP).stripTrailingZeros();case "分钟","分鐘","分"->v.divide(BigDecimal.valueOf(1440),8,RoundingMode.HALF_UP).stripTrailingZeros();case "秒"->v.divide(BigDecimal.valueOf(86400),8,RoundingMode.HALF_UP).stripTrailingZeros();default->v.stripTrailingZeros();}; }
    public static String normalizeText(String value){ if(value==null)return ""; return Normalizer.normalize(value,Normalizer.Form.NFKC).replace('\u3000',' ').replaceAll("\\s+"," ").trim().toLowerCase(Locale.ROOT).replaceAll("[，。！？；：、,.!?;:/\\\\]+"," ").replaceAll("\\s+"," ").trim(); }
    private static String toHalfWidth(String v){return Normalizer.normalize(v==null?"":v,Normalizer.Form.NFKC);}
    public enum ScopeRelation{KNOWN,UNKNOWN}
    public record Scope(ScopeRelation relation,Map<String,String> fields){public Scope{fields=fields==null?Map.of():Map.copyOf(fields);}}
    public record NumericRange(BigDecimal lower,BigDecimal upper,boolean lowerInclusive,boolean upperInclusive,String category){}
    public record NormalizedFact(String normalizedQuestion,String normalizedStatement,String polarity,Map<String,BigDecimal> numericValues,Map<String,NumericRange> numericRanges,Map<String,String> temporalValues,Set<String> enumValues,List<String> processSteps,Scope scope,List<Long> evidenceChunkIds,String originalQuestion,String originalStatement){public NormalizedFact{numericValues=Map.copyOf(numericValues);numericRanges=Map.copyOf(numericRanges);temporalValues=Map.copyOf(temporalValues);enumValues=Set.copyOf(enumValues);processSteps=List.copyOf(processSteps);evidenceChunkIds=List.copyOf(evidenceChunkIds);}}
}
