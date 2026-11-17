package cn.erp.gl;

import java.math.*;
import java.util.*;

/** 财务公式只解析确定的算术和取数函数，不调用脚本、反射或通用表达式引擎。 */
public final class GlFormula {
    private static final MathContext MATH = new MathContext(34, RoundingMode.HALF_UP);
    private static final Set<String> FUNCTIONS =
            Set.of(
                    "QC", "QM", "JF", "DF", "LJ", "XJ", "R", "CAT", "SY", "PL_NET", "CASH_QC",
                    "CASH_QM");

    private GlFormula() {}

    @FunctionalInterface
    public interface Context {
        BigDecimal value(String function, List<String> arguments);
    }

    private interface Node {
        BigDecimal evaluate(Context context);
    }

    public record Expression(
            String source, Set<String> rowReferences, Set<String> subjectCodes, Node tree) {
        public BigDecimal evaluate(Context context) {
            var result = tree.evaluate(context);
            if (result.precision() - result.scale() > 24) throw GlSupport.invalid("报表公式计算结果超出金额范围");
            return result;
        }
    }

    public static Expression parse(String source) {
        if (source == null || source.isBlank() || source.length() > 1500)
            throw GlSupport.invalid("报表公式须为1至1500个字符");
        var parser = new Parser(source);
        var tree = parser.expression(0);
        parser.space();
        if (parser.index != source.length()) throw parser.error();
        return new Expression(
                source, Set.copyOf(parser.references), Set.copyOf(parser.subjects), tree);
    }

    private record Constant(BigDecimal value) implements Node {
        public BigDecimal evaluate(Context context) {
            return value;
        }
    }

    private record Call(String function, List<String> arguments) implements Node {
        public BigDecimal evaluate(Context context) {
            return Objects.requireNonNull(context.value(function, arguments), "取数函数不能返回空值");
        }
    }

    private record Binary(char operator, Node left, Node right) implements Node {
        public BigDecimal evaluate(Context context) {
            var a = left.evaluate(context);
            var b = right.evaluate(context);
            return switch (operator) {
                case '+' -> a.add(b, MATH);
                case '-' -> a.subtract(b, MATH);
                case '*' -> a.multiply(b, MATH);
                case '/' -> {
                    if (b.signum() == 0) throw GlSupport.invalid("报表公式不能除以零，请核对公式和期间数据");
                    yield a.divide(b, MATH);
                }
                default -> throw GlSupport.invalid("报表运算符无效");
            };
        }
    }

    private static final class Parser {
        final String source;
        int index, nodes;
        final Set<String> references = new LinkedHashSet<>(), subjects = new LinkedHashSet<>();

        Parser(String source) {
            this.source = source;
        }

        Node expression(int depth) {
            Node result = term(depth);
            while (true) {
                space();
                if (!peek('+') && !peek('-')) return result;
                char operator = source.charAt(index++);
                result = new Binary(operator, result, term(depth));
                count();
            }
        }

        Node term(int depth) {
            Node result = factor(depth);
            while (true) {
                space();
                if (!peek('*') && !peek('/')) return result;
                char operator = source.charAt(index++);
                result = new Binary(operator, result, factor(depth));
                count();
            }
        }

        Node factor(int depth) {
            if (depth > 24) throw GlSupport.invalid("报表公式嵌套最多24层");
            space();
            count();
            if (take('+')) return factor(depth + 1);
            if (take('-')) return new Binary('-', new Constant(BigDecimal.ZERO), factor(depth + 1));
            if (take('(')) {
                Node value = expression(depth + 1);
                space();
                if (!take(')')) throw error();
                return value;
            }
            if (index >= source.length()) throw error();
            if (Character.isDigit(source.charAt(index))) {
                int start = index;
                while (index < source.length()
                        && (Character.isDigit(source.charAt(index)) || source.charAt(index) == '.'))
                    index++;
                String value = source.substring(start, index);
                if (!value.matches("[0-9]+(?:\\.[0-9]{1,12})?")) throw error();
                var number = new BigDecimal(value);
                if (number.precision() > 30) throw GlSupport.invalid("公式常数过长");
                return new Constant(number);
            }
            String function = identifier();
            if (!FUNCTIONS.contains(function)) throw GlSupport.invalid("报表公式包含不支持的函数");
            space();
            if (!take('(')) throw error();
            var arguments = new ArrayList<String>();
            space();
            if (!take(')'))
                while (true) {
                    arguments.add(argument());
                    space();
                    if (take(')')) break;
                    if (!take(',')) throw error();
                    if (arguments.size() >= 3) throw error();
                }
            validate(function, arguments);
            if (function.equals("R")) references.add(arguments.getFirst());
            if (Set.of("QC", "QM", "JF", "DF", "LJ").contains(function))
                subjects.add(arguments.getFirst());
            return new Call(function, List.copyOf(arguments));
        }

        String identifier() {
            space();
            int start = index;
            while (index < source.length()
                    && (Character.isLetterOrDigit(source.charAt(index))
                            || source.charAt(index) == '_')) index++;
            if (start == index || index - start > 40) throw error();
            return source.substring(start, index);
        }

        String argument() {
            space();
            boolean quoted = take('"');
            String value = identifier();
            if (quoted && !take('"')) throw error();
            return value;
        }

        void validate(String function, List<String> args) {
            int max =
                    Set.of("QC", "QM", "CAT").contains(function)
                            ? 2
                            : Set.of("SY", "PL_NET", "CASH_QC", "CASH_QM").contains(function)
                                    ? 0
                                    : 1;
            int min = max == 2 ? 1 : max;
            if (args.size() < min || args.size() > max) throw GlSupport.invalid("报表函数参数数量无效");
            if (Set.of("QC", "QM", "JF", "DF", "LJ").contains(function)
                    && !args.getFirst().matches("[0-9]{1,32}"))
                throw GlSupport.invalid("报表科目编码只能包含数字");
            if (args.size() == 2 && !Set.of("D", "C", "借方", "贷方").contains(args.get(1)))
                throw GlSupport.invalid("余额方向只能为借方或贷方");
            if (function.equals("CAT")
                    && !Set.of("asset", "liability", "equity", "cost", "common", "income_expense")
                            .contains(args.getFirst())) throw GlSupport.invalid("科目类别无效");
            if (function.equals("R") && !args.getFirst().matches("[A-Za-z0-9_]{1,40}"))
                throw GlSupport.invalid("报表行编码无效");
        }

        void count() {
            if (++nodes > 600) throw GlSupport.invalid("报表公式过于复杂");
        }

        void space() {
            while (index < source.length() && Character.isWhitespace(source.charAt(index))) index++;
        }

        boolean peek(char ch) {
            return index < source.length() && source.charAt(index) == ch;
        }

        boolean take(char ch) {
            if (peek(ch)) {
                index++;
                return true;
            }
            return false;
        }

        cn.erp.shared.ApiException error() {
            return GlSupport.invalid("报表公式语法无效，请核对括号、参数及运算符");
        }
    }
}
