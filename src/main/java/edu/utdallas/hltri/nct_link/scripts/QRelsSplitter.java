package edu.utdallas.hltri.nct_link.scripts;

import edu.utdallas.hltri.framework.Commands;
import edu.utdallas.hltri.inquire.eval.QRels;
import edu.utdallas.hltri.inquire.eval.QRels.Relevance;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

public class QRelsSplitter implements Runnable {
  @Option(names = {"--num-train", "-R"},
  description = "number of topics to use for training")
  int numTrain = 300;

  @Option(names = {"--num-devel", "-D"},
      description = "number of topics to use for development")
  int numDevel = 100;

  @Option(names = {"--num-test", "-E"},
      description = "number of topics to use for testing")
  int numTest = 100;

  @Option(names = {"--seed", "-s"},
      description = "random seed")
  int seed = 1337;

  @Parameters(index = "0",
      paramLabel = "QRELS-PATH")
  private Path qrelsPath;

  @Parameters(index = "1",
  paramLabel = "OUTPUT-FOLDER")
  private Path outputPath;

  private void writeQRelsSubset(QRels qrels, Iterable<String> subset, Path path) {
    try (BufferedWriter writer = Files.newBufferedWriter(path)) {
      for (String topic : subset) {
        for (Map.Entry<String, Relevance> entry : qrels.getJudgements(topic).entrySet()) {
          writer.append(topic)
              .append(" 0 ")
              .append(entry.getKey())
              .append(' ')
              .append(Integer.toString(entry.getValue().toInt()));
          writer.newLine();
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void run() {
    try {
      Files.createDirectories(outputPath);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    // Get all trials
    final QRels qrels = QRels.fromFile(qrelsPath);

    final List<String> topics = new ArrayList<>(qrels.getTopics());

    // Sample randomly without replacement
    final Random random = new Random(seed);
    Collections.shuffle(topics, random);

    writeQRelsSubset(qrels,
        topics.subList(0, numTrain),
        outputPath.resolve("qrels.train.txt"));

    writeQRelsSubset(qrels,
        topics.subList(numTrain, numTrain + numDevel),
        outputPath.resolve("qrels.devel.txt"));

    writeQRelsSubset(qrels,
        topics.subList(numTrain + numDevel, numTrain + numDevel + numTest),
        outputPath.resolve("qrels.test.txt"));
  }

  public static void main(String... args) {
    Commands.run(new QRelsSplitter(), args);
  }
}
