package edu.utdallas.hltri.nct_link.scripts;

import edu.utdallas.hltri.data.medline.jaxb.JaxbMedlineSearchEngine;
import edu.utdallas.hltri.nct_link.NctLinker.RetrievedNctLinker;
import edu.utdallas.hltri.nct_link.search.ExactMedlineTrialQueryFactory;
import edu.utdallas.hltri.nct_link.search.SimpleMedlineTrialQueryFactory;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import edu.utdallas.hlt.medbase.umls.UMLSManager;
import edu.utdallas.hltri.data.clinical_trials.JaxbClinicalTrial;
import edu.utdallas.hltri.framework.Commands;
import edu.utdallas.hltri.nct_link.Experiments;
import edu.utdallas.hltri.nct_link.NctLinkSettings;
import edu.utdallas.hltri.nct_link.NctLinker;
import edu.utdallas.hltri.nct_link.NctLinker.AnalyzedNctLinker;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

public class Baseline implements Runnable {
  @Parameters(index = "0", paramLabel = "TOPICS",
      description = "xml file containing official (or extra) TREC-PM topics")
  private Path topicsPath;

  @Parameters(index = "1", paramLabel = "OUTPUT-FILE")
  private Path outputPath;

  @Option(names =  { "--method"},
      paramLabel = "search-method")
  private String method = "full";

  @Override
  public void run() {
    final NctLinkSettings settings = NctLinkSettings.getDefault();

    // Get all trials
    List<JaxbClinicalTrial> trials = Experiments.getTrialsWithPmids(topicsPath);

    // Sample 100 of them randomly without replacement
    final Random random = new Random(1337);
    Collections.shuffle(trials, random);
    trials = trials.subList(0, 100);

    final NctLinker linker = new NctLinker(trials);

    final AnalyzedNctLinker analyzedLinker = linker.analyzeTrials();

    try (UMLSManager umls = new UMLSManager()) {
      analyzedLinker.expandConditions(umls.withFixedWeight(.75));
    }

    RetrievedNctLinker self;
    switch (this.method) {
      case "full":
        self = analyzedLinker.search();
        break;

      case "simple": {
          final SimpleMedlineTrialQueryFactory factory = new SimpleMedlineTrialQueryFactory();
          self = analyzedLinker.search(factory::getQuery, JaxbMedlineSearchEngine.getJaxbLazy());
        }
        break;

      case "exact": {
          final ExactMedlineTrialQueryFactory factory = new ExactMedlineTrialQueryFactory();
          self = analyzedLinker.search(factory::getQuery, JaxbMedlineSearchEngine.getJaxbLazy());
        }
        break;

      default:
        throw new UnsupportedOperationException("unsupported method \"" + method + "\"!");
    }
    self.writeAsTrecRun(outputPath);
  }

  public static void main(String... args) {
    Commands.run(new Baseline(), args);
  }
}
