package edu.utdallas.hltri.nct_link.scripts;

import com.joestelmach.natty.Parser;
import com.joestelmach.natty.DateGroup;

import edu.utdallas.hltri.data.clinical_trials.ClinicalTrialParser;
import edu.utdallas.hltri.data.clinical_trials.jaxb.ClinicalStudy;
import edu.utdallas.hltri.framework.Commands;
import edu.utdallas.hltri.framework.ProgressLogger;
import edu.utdallas.hltri.logging.Logger;
import edu.utdallas.hltri.util.Unsafe;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Date;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import picocli.CommandLine.Parameters;

public class GenerateNctPmidQrels implements Runnable {
  private static final Logger log = Logger.get(GenerateNctPmidQrels.class);

  @Parameters(index = "0",
              paramLabel = "QRELS-PATH")
  private Path qrelsPath;

  @Parameters(index = "1..*",
              arity = "1..*",
              paramLabel = "NCT-XML-PATHS")
  private Path[] nctPaths;


  private Parser natty = new Parser();

  private Optional<Date> tryParseDate(String line) {
    if (line == null) {
      return Optional.empty();
    }
    try {
      final List<DateGroup> groups = natty.parse(line);
      for (DateGroup group : groups) {
        for (Date date : group.getDates()) {
          return Optional.of(date);
        }
      }
      // Parser throws these sometimes when creating a Calendar, so give up if we hit one
    } catch (NullPointerException e) {
      return Optional.empty();
    }
    return Optional.empty();
  }

  private Stream<CharSequence> getQrels(ClinicalStudy study) {
    Date studyDate = Optional.ofNullable(study.getStartDate())
        .flatMap(date -> this.tryParseDate(date.getValue()))
        .orElse(new Date());

    return study.getResultsReference().stream()
        .filter(struct -> struct.getPMID() != null)
        .filter(struct -> this.tryParseDate(struct.getCitation()).orElse(new Date(0)).after(studyDate))
        .map(struct -> study.getIdInfo().getNctId() + " 0 " + struct.getPMID() + " 1\n");
  }

  @Override
  public void run() {
    final ClinicalTrialParser parser = new ClinicalTrialParser();
    try (BufferedWriter writer = Files.newBufferedWriter(qrelsPath);
        ProgressLogger plog = ProgressLogger.indeterminateSize("parsing", 1, TimeUnit.MINUTES)) {
      Arrays.stream(nctPaths)
          .flatMap(Unsafe.function(start -> Files.walk(start, FileVisitOption.FOLLOW_LINKS)))
          .filter(path -> path.getFileName().toString().endsWith(".xml"))
          .map(parser::parseXmlFile)
          .peek(study -> plog.update("parsing {}", study.getIdInfo().getNctId()))
          .flatMap(this::getQrels)
          .forEach(Unsafe.consumer(writer::append));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static void main(String... args) {
    Commands.run(new GenerateNctPmidQrels(), args);
  }
}
