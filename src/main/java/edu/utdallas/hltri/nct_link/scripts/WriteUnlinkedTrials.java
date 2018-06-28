package edu.utdallas.hltri.nct_link.scripts;

import edu.utdallas.hltri.data.clinical_trials.ClinicalTrialParser;
import edu.utdallas.hltri.data.clinical_trials.jaxb.ClinicalStudy;
import edu.utdallas.hltri.framework.Commands;
import edu.utdallas.hltri.framework.ProgressLogger;
import edu.utdallas.hltri.logging.Logger;
import edu.utdallas.hltri.util.Unsafe;
import edu.utdallas.hltri.util.Unsafe.CheckedRunnable;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import picocli.CommandLine.Parameters;

public class WriteUnlinkedTrials implements CheckedRunnable {
  private static final Logger log = Logger.get(GenerateNctPmidQrels.class);

  @Parameters(index = "0",
      paramLabel = "OUTPUT-FILE")
  private Path outputFile;

  @Parameters(index = "1..*",
      arity = "1..*",
      paramLabel = "NCT-XML-PATHS")
  private Path[] nctPaths;


  private boolean studyIsUnlinked(ClinicalStudy study) {
    return study.getResultsReference().isEmpty();
  }

  public void run() throws IOException {
    log.info("Parsing data from {}...", (Object) nctPaths);
    final List<Path> files = Arrays.stream(nctPaths)
        .flatMap(Unsafe.function(start -> Files.walk(start, FileVisitOption.FOLLOW_LINKS)))
        .filter(path -> path.getFileName().toString().endsWith(".xml"))
        .collect(Collectors.toList());

    final ClinicalTrialParser parser = new ClinicalTrialParser();

    log.info("Writing nctIds to {}...", outputFile);
    try (final ProgressLogger plog = ProgressLogger.fixedSize("parsing", files.size(), 1, TimeUnit.MINUTES)) {
      Iterable<String> nctIds = files.stream()
          .map(parser::parseXmlFile)
          .peek(study -> plog.update("parsed {}", study.getIdInfo().getNctId()))
          .filter(this::studyIsUnlinked)
          .map(study -> study.getIdInfo().getNctId())::iterator;
      Files.write(outputFile, nctIds);
    }
  }

  public static void main(String... args) throws Exception {
    Commands.run(new WriteUnlinkedTrials(), args);
  }

}
