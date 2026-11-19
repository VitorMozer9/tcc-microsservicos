import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Inicia o JarLauncher original e permite ao script acionar os shutdown hooks da JVM. */
public class AplicacaoDeTeste {
    public static void main(String[] args) throws Exception {
        Path sinal = Path.of(args[0]);
        Thread.ofPlatform().daemon().name("encerramento-do-teste").start(() -> {
            try {
                while (!Files.exists(sinal)) Thread.sleep(150);
                System.exit(0);
            } catch (InterruptedException erro) {
                Thread.currentThread().interrupt();
            }
        });
        Class.forName("org.springframework.boot.loader.launch.JarLauncher")
                .getMethod("main", String[].class)
                .invoke(null, (Object) Arrays.copyOfRange(args, 1, args.length));
    }
}
