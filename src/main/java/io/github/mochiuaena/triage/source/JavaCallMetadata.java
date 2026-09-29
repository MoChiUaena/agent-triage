package io.github.mochiuaena.triage.source;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.lang.model.element.Modifier;
import java.util.*;
import static io.github.mochiuaena.triage.source.SourceModels.*;

/** Lexical facts only. No compiler analysis, dependency loading or Spring bean instantiation. */
final class JavaCallMetadata {
    private JavaCallMetadata() {}

    static TypeInfo type(CompilationUnitTree unit, ClassTree node, String name) {
        var parents = new ArrayList<String>();
        if (node.getExtendsClause() != null) parents.add(typeName(node.getExtendsClause()));
        node.getImplementsClause().forEach(value -> parents.add(typeName(value)));
        var fields = new LinkedHashMap<String,String>();
        for (var member : node.getMembers()) if (member instanceof VariableTree field)
            fields.put(field.getName().toString(), typeName(field.getType()));
        var imports = unit.getImports().stream().filter(value -> !value.isStatic()).map(value -> value.getQualifiedIdentifier().toString()).toList();
        var statics = unit.getImports().stream().filter(ImportTree::isStatic).map(value -> value.getQualifiedIdentifier().toString()).toList();
        boolean abstractType = node.getKind() == Tree.Kind.INTERFACE || node.getModifiers().getFlags().contains(Modifier.ABSTRACT);
        return new TypeInfo(name, unit.getPackageName() == null ? "" : unit.getPackageName().toString(), node.getKind().name(), abstractType, List.copyOf(parents), Map.copyOf(fields), imports, statics);
    }

    static MethodInfo method(CompilationUnitTree unit, MethodTree node, TypeInfo owner, SourcePositions positions) {
        var parameters = node.getParameters().stream().map(value -> new Parameter(value.getName().toString(), typeName(value.getType()))).toList();
        var invocations = new ArrayList<Invocation>();
        boolean[] truncated = {false};
        new TreeScanner<Void,Void>() {
            final Deque<Map<String,String>> scopes = new ArrayDeque<>();
            int deferred;
            {
                var args = new HashMap<String,String>(); parameters.forEach(value -> args.put(value.name(), value.type())); scopes.push(args);
            }
            @Override public Void visitBlock(BlockTree block, Void unused) {
                scopes.push(new HashMap<>());
                try { return super.visitBlock(block, unused); } finally { scopes.pop(); }
            }
            @Override public Void visitForLoop(ForLoopTree loop, Void unused) {
                scopes.push(new HashMap<>());
                try { return super.visitForLoop(loop, unused); } finally { scopes.pop(); }
            }
            @Override public Void visitEnhancedForLoop(EnhancedForLoopTree loop, Void unused) {
                scopes.push(new HashMap<>());
                try { return super.visitEnhancedForLoop(loop, unused); } finally { scopes.pop(); }
            }
            @Override public Void visitCatch(CatchTree value, Void unused) {
                scopes.push(new HashMap<>());
                try { return super.visitCatch(value, unused); } finally { scopes.pop(); }
            }
            @Override public Void visitTry(TryTree value, Void unused) {
                scopes.push(new HashMap<>());
                try { return super.visitTry(value, unused); } finally { scopes.pop(); }
            }
            @Override public Void visitLambdaExpression(LambdaExpressionTree lambda, Void unused) {
                scopes.push(new HashMap<>()); deferred++;
                try {
                    for (var parameter : lambda.getParameters()) scopes.peek().put(parameter.getName().toString(), typeName(parameter.getType()));
                    return scan(lambda.getBody(), unused);
                } finally { deferred--; scopes.pop(); }
            }
            @Override public Void visitClass(ClassTree nested, Void unused) { return null; }
            @Override public Void visitVariable(VariableTree variable, Void unused) {
                String type = typeName(variable.getType());
                if (type.equals("var") || variable.getType() == null) type = infer(variable.getInitializer());
                scan(variable.getInitializer(), unused);
                scopes.peek().put(variable.getName().toString(), type);
                return null;
            }
            @Override public Void visitAssignment(AssignmentTree assignment, Void unused) {
                // The declared type is retained; runtime assignments are not interpreted as bean selection.
                return super.visitAssignment(assignment, unused);
            }
            @Override public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
                if (invocations.size() >= 30) { truncated[0] = true; return null; }
                var chain = new ArrayList<String>();
                ExpressionTree receiver = null;
                String name;
                if (call.getMethodSelect() instanceof MemberSelectTree select) {
                    receiver = select.getExpression(); name = select.getIdentifier().toString();
                } else name = call.getMethodSelect().toString();
                ExpressionTree base = base(receiver, chain);
                chain.add(name);
                String baseType = base == null ? owner.name() : infer(base);
                boolean namedType = base instanceof IdentifierTree identifier && !known(identifier.getName().toString())
                    && !Set.of("this", "super").contains(identifier.getName().toString());
                boolean staticReceiver = namedType || base == null && node.getModifiers().getFlags().contains(Modifier.STATIC);
                if (namedType) baseType = ((IdentifierTree) base).getName().toString();
                long start = positions.getStartPosition(unit, call);
                if (start >= 0) {
                    String prefix = base == null ? "this" : safeReceiver(base);
                    String expression = prefix + "." + String.join("().", chain);
                    if (expression.length() > 240) expression = expression.substring(0, 240);
                    invocations.add(new Invocation(expression, baseType, staticReceiver, name,
                        call.getArguments().stream().map(this::infer).toList(), List.copyOf(chain), (int) unit.getLineMap().getLineNumber(start), deferred > 0));
                }
                // A fluent chain is one operation. Nested arguments remain separate calls.
                scan(call.getArguments(), unused);
                if (receiver != null) scanChainArguments(receiver, unused);
                return null;
            }
            @Override public Void visitMemberReference(MemberReferenceTree reference, Void unused) {
                if (invocations.size() >= 30) { truncated[0] = true; return null; }
                long start = positions.getStartPosition(unit, reference);
                if (start >= 0) invocations.add(new Invocation(safeReceiver(reference.getQualifierExpression()) + "::" + reference.getName(),
                    infer(reference.getQualifierExpression()), false, reference.getName().toString(), List.of(), List.of(reference.getName().toString()),
                    (int) unit.getLineMap().getLineNumber(start), true));
                return null;
            }
            private void scanChainArguments(ExpressionTree expression, Void unused) {
                if (expression instanceof MethodInvocationTree invocation) {
                    scan(invocation.getArguments(), unused);
                    if (invocation.getMethodSelect() instanceof MemberSelectTree select) scanChainArguments(select.getExpression(), unused);
                } else scan(expression, unused);
            }
            private boolean known(String name) {
                return scopes.stream().anyMatch(scope -> scope.containsKey(name)) || owner.fields().containsKey(name);
            }
            private String lookup(String name) {
                for (var scope : scopes) if (scope.containsKey(name)) return scope.get(name);
                return owner.fields().getOrDefault(name, "?");
            }
            private String infer(ExpressionTree expression) {
                if (expression == null) return "?";
                if (expression instanceof IdentifierTree identifier) {
                    if (identifier.getName().contentEquals("this")) return owner.name();
                    if (identifier.getName().contentEquals("super")) return "?";
                    return lookup(identifier.getName().toString());
                }
                if (expression instanceof MemberSelectTree select && select.getExpression() instanceof IdentifierTree identifier
                        && identifier.getName().contentEquals("this")) return owner.fields().getOrDefault(select.getIdentifier().toString(), "?");
                if (expression instanceof ParenthesizedTree value) return infer(value.getExpression());
                if (expression instanceof TypeCastTree cast) return typeName(cast.getType());
                if (expression instanceof NewClassTree created) return typeName(created.getIdentifier());
                if (expression instanceof NewArrayTree array && array.getType() != null) return typeName(array.getType()) + "[]";
                if (expression instanceof LiteralTree literal) return switch (literal.getKind()) {
                    case STRING_LITERAL -> "java.lang.String";
                    case CHAR_LITERAL -> "char";
                    case BOOLEAN_LITERAL -> "boolean";
                    case INT_LITERAL -> "int";
                    case LONG_LITERAL -> "long";
                    case FLOAT_LITERAL -> "float";
                    case DOUBLE_LITERAL -> "double";
                    case NULL_LITERAL -> "<null>";
                    default -> "?";
                };
                return "?";
            }
        }.scan(node.getBody(), null);
        return new MethodInfo(parameters, node.getReturnType() == null ? "" : typeName(node.getReturnType()),
            node.getModifiers().getFlags().contains(Modifier.STATIC), node.getBody() == null,
            !node.getParameters().isEmpty() && node.getParameters().getLast().toString().contains("..."), List.copyOf(invocations), truncated[0]);
    }

    private static ExpressionTree base(ExpressionTree receiver, List<String> chain) {
        if (receiver instanceof MethodInvocationTree invocation && invocation.getMethodSelect() instanceof MemberSelectTree select) {
            ExpressionTree base = base(select.getExpression(), chain); chain.add(select.getIdentifier().toString()); return base;
        }
        return receiver;
    }
    private static String safeReceiver(ExpressionTree expression) {
        if (expression instanceof IdentifierTree identifier) return identifier.getName().toString();
        if (expression instanceof MemberSelectTree select) return safeReceiver(select.getExpression()) + "." + select.getIdentifier();
        if (expression instanceof NewClassTree created) return "new " + typeName(created.getIdentifier());
        return "<expression>";
    }
    static String typeName(Tree tree) {
        if (tree == null) return "?";
        if (tree instanceof AnnotatedTypeTree annotated) return typeName(annotated.getUnderlyingType());
        return tree.toString();
    }
}
