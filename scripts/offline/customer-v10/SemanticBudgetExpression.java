import java.util.*;
import java.util.regex.*;

/** Bounded grammar for the two additive semantic numeric budget checks only. */
public final class SemanticBudgetExpression {
    static boolean scoped(String table,String constraint){
        return (table.equals("CONTRACT_SEMANTIC_PLAN")&&constraint.equals("CK_SEM_PLAN_BUDGET"))
            ||(table.equals("CONTRACT_SEMANTIC_UNIT")&&constraint.equals("CK_SEM_UNIT_COUNTER"));
    }
    record Node(String value,boolean predicate) {}
    final List<String> tokens=new ArrayList<>(); int position,depth;
    SemanticBudgetExpression(String source){
        if(source==null||source.length()>32768)throw new IllegalArgumentException();
        var matcher=Pattern.compile("\"[A-Za-z_][A-Za-z0-9_]*\"|[A-Za-z_][A-Za-z0-9_]*|[0-9]+|<=|>=|<>|!=|[=<>+()]").matcher(source);
        int end=0;
        while(matcher.find()){
            require(source.substring(end,matcher.start()).isBlank());String token=matcher.group();
            if(token.startsWith("\"")){token=token.substring(1,token.length()-1);require(token.matches("[A-Z_][A-Z0-9_]*"));}
            tokens.add(token.toUpperCase(Locale.ROOT));end=matcher.end();
        }
        require(source.substring(end).isBlank());require(tokens.size()<=4096);
    }
    static boolean equivalent(String expected,String observed){
        try{return canonical(expected).equals(canonical(observed));}
        catch(IllegalArgumentException unsupported){return false;}
    }
    static String canonical(String source){
        var parser=new SemanticBudgetExpression(source);Node result=parser.and();
        require(result.predicate&&parser.position==parser.tokens.size());return result.value;
    }
    static void require(boolean test){if(!test)throw new IllegalArgumentException();}
    Node and(){
        var children=new ArrayList<String>();Node first=compare();children.add(first.value);
        while(eat("AND")){require(first.predicate);Node next=compare();require(next.predicate);children.add(next.value);}
        if(children.size()==1)return first;
        return new Node("AND("+String.join(",",children.stream().map(s->s.startsWith("AND(")?s.substring(4,s.length()-1):s).toList())+")",true);
    }
    Node compare(){
        Node left=add();
        if(position<tokens.size()&&Set.of("=","<>","!=","<",">","<=",">=").contains(tokens.get(position))){
            String op=tokens.get(position++);Node right=add();require(!left.predicate&&!right.predicate);
            return new Node((op.equals("!=")?"<>":op)+"("+left.value+","+right.value+")",true);
        }
        return left;
    }
    Node add(){
        Node value=primary();while(eat("+")){Node right=primary();require(!value.predicate&&!right.predicate);value=new Node("+("+value.value+","+right.value+")",false);}return value;
    }
    Node primary(){
        if(eat("(")){require(++depth<=128);Node inner=and();require(eat(")"));depth--;return inner;}
        require(position<tokens.size());String token=tokens.get(position++);
        require(token.matches("[A-Z_][A-Z0-9_]*|[0-9]+")&&!Set.of("AND","OR","NOT","IS","NULL","IN","BETWEEN","TRUE","FALSE").contains(token));
        return new Node(token,false);
    }
    boolean eat(String token){if(position<tokens.size()&&tokens.get(position).equals(token)){position++;return true;}return false;}
}
