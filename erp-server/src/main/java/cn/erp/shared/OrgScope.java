package cn.erp.shared;

import java.util.*;

/** 部门条件的合取；每个条件内部的部门及本人范围取并集，供任务快照与当前授权求交集。 */
public record OrgScope(List<Clause> clauses,long primaryDepartmentId) {
    public record Clause(Set<Long> departmentIds,Set<Long> selfUserIds) {
        public Clause { departmentIds=Set.copyOf(departmentIds);selfUserIds=Set.copyOf(selfUserIds); }
    }
    public OrgScope { clauses=List.copyOf(clauses); }
    public static OrgScope all() { return new OrgScope(List.of(),0); }
    public static OrgScope restricted(Set<Long> departments,Set<Long> users,long primary) {
        return new OrgScope(List.of(new Clause(departments,users)),primary);
    }
    public boolean allDepartments() { return clauses.isEmpty(); }
    public OrgScope intersect(OrgScope other) {
        var combined=new ArrayList<>(clauses);combined.addAll(other.clauses());
        return new OrgScope(combined,primaryDepartmentId);
    }
    public boolean allows(long department,long user) {
        return allDepartments() || department>0 && clauses.stream().allMatch(c->c.departmentIds().contains(department)||c.selfUserIds().contains(user));
    }
    public String ownerFilter(String alias) {
        if(!alias.matches("[a-zA-Z_][a-zA-Z0-9_]*")) throw new IllegalArgumentException("归属查询别名无效");
        if(allDepartments()) return "1=1";
        String prefix=alias+".";
        return prefix+"owner_department_id>0 AND "+clauses.stream().map(c->"("+in(prefix+"owner_department_id",c.departmentIds())+" OR "+in(prefix+"owner_user_id",c.selfUserIds())+")").reduce((a,b)->a+" AND "+b).orElse("1=0");
    }
    public void appendOwnerFilter(StringBuilder sql,List<Object> args,String alias) { sql.append(" AND (").append(ownerFilter(alias)).append(')'); }
    private static String in(String field,Set<Long> values) {
        if(values.isEmpty()) return "1=0";
        if(values.stream().anyMatch(id->id<=0)) throw new IllegalArgumentException("归属范围编号无效");
        return field+" IN ("+values.stream().sorted().map(String::valueOf).reduce((a,b)->a+","+b).orElseThrow()+")";
    }
    public String signature() {
        return clauses.stream().map(c->c.departmentIds().stream().sorted().toList()+":"+c.selfUserIds().stream().sorted().toList()).sorted().toList().toString();
    }
    public boolean covers(OrgScope result) {
        if(allDepartments()) return true;
        if(result.allDepartments()) return false;
        var departments=new HashSet<Long>();var users=new HashSet<Long>();users.add(0L);
        for(var scope:List.of(this,result)) for(var clause:scope.clauses()) { departments.addAll(clause.departmentIds());users.addAll(clause.selfUserIds()); }
        long outside=1;while(departments.contains(outside)) outside++;departments.add(outside);
        for(long department:departments) for(long user:users) if(result.allows(department,user)&&!allows(department,user)) return false;
        return true;
    }
}
