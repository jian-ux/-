package com.feisheng.bot.admin.service;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;

/** Compares normalized facts conservatively; unknown scope/value never becomes a conflict. */
@Service
public class FactComparisonService {
    public ComparisonResult compare(FactNormalizationService.NormalizedFact left, FactNormalizationService.NormalizedFact right) {
        Objects.requireNonNull(left, "left"); Objects.requireNonNull(right, "right");
        if (left.scope().relation() == FactNormalizationService.ScopeRelation.UNKNOWN || right.scope().relation() == FactNormalizationService.ScopeRelation.UNKNOWN)
            return result(Relation.UNKNOWN, ConflictType.SCOPE, Severity.BLOCKING, List.of("scope"), "适用范围未知，无法比较");
        if (mutuallyExclusive(left.scope(), right.scope()))
            return result(Relation.SCOPE_DIFFERENCE, ConflictType.SCOPE, Severity.INFO, List.of("scope"), "适用范围互斥");
        List<String> fields = new ArrayList<>(); ConflictType type = ConflictType.OTHER;
        if (knownDifferent(left.polarity(), right.polarity())) { fields.add("polarity"); type = ConflictType.POLARITY; }
        if (!numericValuesEquivalent(left.numericValues(), right.numericValues())) { fields.add("numericValues"); type = classifyNumeric(left, right); }
        if (!rangesEquivalent(left.numericRanges(), right.numericRanges())) { fields.add("numericRanges"); type = ConflictType.LIMIT; }
        if (!Objects.equals(left.temporalValues(), right.temporalValues()) && !left.temporalValues().isEmpty() && !right.temporalValues().isEmpty()) { fields.add("temporalValues"); type = ConflictType.DATE; }
        if (!Objects.equals(left.processSteps(), right.processSteps()) && !left.processSteps().isEmpty() && !right.processSteps().isEmpty()) { fields.add("processSteps"); type = ConflictType.PROCESS; }
        if (!Objects.equals(left.enumValues(), right.enumValues()) && !left.enumValues().isEmpty() && !right.enumValues().isEmpty()) { fields.add("enumValues"); type = ConflictType.ENUM; }
        if (!fields.isEmpty()) {
            if (type == ConflictType.ENUM) return result(Relation.UNKNOWN, type, Severity.BLOCKING, fields, "枚举值或表述无法自动合并");
            return result(Relation.CONFLICT, type, severityFor(type), fields, "重叠范围内事实结论不一致");
        }
        if (Objects.equals(left.normalizedQuestion(), right.normalizedQuestion()) && (Objects.equals(left.normalizedStatement(), right.normalizedStatement()) || numericValuesEquivalent(left.numericValues(), right.numericValues())))
            return result(Relation.NOT_CONFLICT, ConflictType.OTHER, Severity.INFO, List.of(), "事实重复");
        return result(Relation.UNKNOWN, ConflictType.OTHER, Severity.BLOCKING, List.of("statement"), "无法确定事实关系");
    }
    private boolean knownDifferent(String a,String b){return !"UNKNOWN".equals(a)&&!"UNKNOWN".equals(b)&&!Objects.equals(a,b);}
    private boolean mutuallyExclusive(FactNormalizationService.Scope a, FactNormalizationService.Scope b){ for(String key:a.fields().keySet()) if(b.fields().containsKey(key)&&!Objects.equals(a.fields().get(key),b.fields().get(key))) return true; return false; }
    private FactComparisonService.ConflictType classifyNumeric(FactNormalizationService.NormalizedFact a, FactNormalizationService.NormalizedFact b){ for(String k:a.numericValues().keySet())if(k.startsWith("amount"))return ConflictType.AMOUNT; return ConflictType.NUMERIC; }
    private boolean numericValuesEquivalent(Map<String,BigDecimal> a,Map<String,BigDecimal> b){ if(a.size()!=b.size())return false; List<BigDecimal>x=new ArrayList<>(a.values()),y=new ArrayList<>(b.values()); boolean[] used=new boolean[y.size()]; for(BigDecimal n:x){boolean found=false;for(int i=0;i<y.size();i++)if(!used[i]&&n.compareTo(y.get(i))==0){used[i]=true;found=true;break;}if(!found)return false;}return true; }
    private boolean rangesEquivalent(Map<String,FactNormalizationService.NumericRange> a,Map<String,FactNormalizationService.NumericRange> b){ if(a.size()!=b.size())return false; for(var e:a.entrySet()){var r=b.get(e.getKey());if(r==null||!eq(r.lower(),e.getValue().lower())||!eq(r.upper(),e.getValue().upper())||r.lowerInclusive()!=e.getValue().lowerInclusive()||r.upperInclusive()!=e.getValue().upperInclusive())return false;}return true; }
    private boolean eq(BigDecimal a,BigDecimal b){return a==null?b==null:b!=null&&a.compareTo(b)==0;}
    private Severity severityFor(ConflictType type){return switch(type){case POLARITY,AMOUNT,NUMERIC,DATE,DURATION,LIMIT,PROCESS->Severity.BLOCKING;default->Severity.WARNING;};}
    private ComparisonResult result(Relation r,ConflictType t,Severity s,List<String> f,String e){return new ComparisonResult(r,t,s,List.copyOf(f),e);}
    public enum Relation{CONFLICT,NOT_CONFLICT,SCOPE_DIFFERENCE,UNKNOWN}
    public enum ConflictType{POLARITY,NUMERIC,AMOUNT,DATE,DURATION,LIMIT,ENUM,PROCESS,SCOPE,OTHER}
    public enum Severity{BLOCKING,WARNING,INFO}
    public record ComparisonResult(Relation relation,ConflictType conflictType,Severity severity,List<String> differingFields,String explanation){}
}
