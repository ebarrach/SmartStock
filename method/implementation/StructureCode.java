import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public class StructureCode {

    private static final Set<String> IGNORED_FOLDERS = Set.of(
            ".git",
            ".idea",
            ".vscode",
            "node_modules",
            "target",
            "build",
            "dist",
            ".gradle",
            "out"
    );

    /**
     * This function writes the directory tree structure to a given file.
     *
     * Parameter:
     * ----------
     * dirPath (Path): The root directory path to start the tree from.
     * writer (BufferedWriter): The writer to which the structure will be written.
     * indent (String): The current indentation level used for subdirectories.
     *
     * Return:
     * -------
     * None
     *
     * Version:
     * --------
     * specification: Esteban Barracho (v.1 09/11/25)
     * implement: Esteban Barracho (v.1 09/11/25)
     */
    private static void writeTree(
            Path dirPath,
            BufferedWriter writer,
            String indent
    ) throws IOException {

        List<Path> items;

        try (Stream<Path> stream = Files.list(dirPath)) {
            items = stream
                    .sorted(Comparator.comparing(
                            path -> path.getFileName().toString()
                    ))
                    .toList();
        }

        for (Path path : items) {

            String item = path.getFileName().toString();

            if (IGNORED_FOLDERS.contains(item)) {
                continue;
            }

            if (Files.isDirectory(path)) {

                writer.write(indent + item + "/");
                writer.newLine();

                writeTree(
                        path,
                        writer,
                        indent + "    "
                );

            } else {

                writer.write(indent + item);
                writer.newLine();
            }
        }
    }

    /**
     * Finds the project root by searching for the .git directory.
     *
     * The search starts from the current working directory
     * and moves upward until the project root is found.
     *
     * Return:
     * -------
     * Path: The project root directory.
     *
     * Version:
     * --------
     * specification: Esteban Barracho (v.1 01/10/26)
     * implement: Esteban Barracho (v.1 01/10/26)
     */
    private static Path findProjectRoot() {

        Path current = Paths.get("")
                .toAbsolutePath()
                .normalize();

        while (current != null) {

            if (Files.isDirectory(current.resolve(".git"))) {
                return current;
            }

            current = current.getParent();
        }

        throw new IllegalStateException(
                "Project root not found: no .git directory found."
        );
    }

    /**
     * This function generates the project structure file named 'structure.txt'.
     *
     * The file is automatically generated at the root of the Git project.
     *
     * Return:
     * -------
     * None
     *
     * Version:
     * --------
     * specification: Esteban Barracho (v.3 01/10/26)
     * implement: Esteban Barracho (v.3 01/10/26)
     */
    public static void generateStructure() throws IOException {

        Path projectRoot = findProjectRoot();

        Path outputPath = projectRoot.resolve("structure.txt");

        try (BufferedWriter writer = Files.newBufferedWriter(
                outputPath,
                StandardCharsets.UTF_8
        )) {

            writer.write("Structure du projet :");
            writer.newLine();
            writer.newLine();

            String rootName = projectRoot
                    .getFileName()
                    .toString();

            writer.write(rootName + "/");
            writer.newLine();

            writeTree(
                    projectRoot,
                    writer,
                    "    "
            );
        }

        System.out.println(
                "structure.txt generated successfully:"
        );

        System.out.println(
                outputPath.toAbsolutePath()
        );
    }

    /**
     * Entry point of the structure generator.
     */
    public static void main(String[] args) {

        try {

            generateStructure();

        } catch (IOException | IllegalStateException e) {

            System.err.println(
                    "Error while generating project structure: "
                            + e.getMessage()
            );
        }
    }
}