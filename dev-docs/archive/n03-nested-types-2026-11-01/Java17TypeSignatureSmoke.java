import dev.brikk.ducklake.catalog.DucklakeTypeSignature;
import dev.brikk.ducklake.catalog.InlinedTypeNode;
import java.util.List;

/** Public Java call surface, with only the compiled catalog classes and Kotlin stdlib. */
public final class Java17TypeSignatureSmoke {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        InlinedTypeNode type = DucklakeTypeSignature.parse(
            "STRUCT<\"a,b\":INT32,\"x:y\":LIST<DECIMAL(18,2)>,\"q\"\"uote\":VARCHAR,MiXeD:BOOLEAN>");
        check(type.getType().equals("struct") && type.getName().isEmpty(), "root type/name changed");
        check(type.getChildren().stream().map(InlinedTypeNode::getName).toList()
            .equals(List.of("a,b", "x:y", "q\"uote", "MiXeD")), "field names/case changed");
        check(type.getChildren().get(1).getChildren().get(0).getType().equals("decimal(18,2)"), "nested decimal changed");
        try {
            DucklakeTypeSignature.parse("struct<a,b:int32>");
            throw new AssertionError("ambiguous input accepted");
        } catch (IllegalArgumentException expected) { }
        try {
            DucklakeTypeSignature.parse("list<".repeat(66) + "int32" + ">".repeat(66));
            throw new AssertionError("unbounded nesting accepted");
        } catch (IllegalArgumentException expected) { }
        System.out.println("Java " + System.getProperty("java.version") + ": public type-signature API PASS");
    }
}
