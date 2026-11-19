import java.sql.DriverManager;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

/** Verifica o arquivo ja encerrado em modo somente leitura, sem criar banco ausente. */
public class TabelasH2 {
    public static void main(String[] args) throws Exception {
        var esperado = Set.copyOf(Arrays.asList(args).subList(1, args.length));
        var tabelas = new TreeSet<String>();
        try (var c = DriverManager.getConnection("jdbc:h2:file:" + args[0] + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r", "sa", "");
             var rs = c.getMetaData().getTables(null, "PUBLIC", "%", new String[]{"TABLE", "BASE TABLE"})) {
            while (rs.next()) tabelas.add(rs.getString("TABLE_NAME"));
        }
        if (!tabelas.equals(esperado)) throw new AssertionError(tabelas + " != " + esperado);
        System.out.println(args[0] + " " + tabelas);
    }
}
