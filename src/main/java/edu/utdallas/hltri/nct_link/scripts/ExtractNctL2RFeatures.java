package edu.utdallas.hltri.nct_link.scripts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import edu.utdallas.hlt.medbase.umls.UMLSManager;
import edu.utdallas.hltri.data.clinical_trials.JaxbClinicalTrial;
import edu.utdallas.hltri.framework.Commands;
import edu.utdallas.hltri.inquire.eval.QRels;
import edu.utdallas.hltri.logging.Logger;
import edu.utdallas.hltri.nct_link.Experiments;
import edu.utdallas.hltri.nct_link.NctLinker;
import edu.utdallas.hltri.nct_link.NctLinker.AnalyzedNctLinker;
import edu.utdallas.hltri.struct.Weighted;
import edu.utdallas.hltri.util.Expander;
import edu.utdallas.hltri.util.IntIdentifier;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

public class ExtractNctL2RFeatures implements Runnable {
  private static final Logger log = Logger.get(ExtractNctL2RFeatures.class);

  @Option(names = {"--test", "--retrieve", "--infer"},
      description = {"use retrieval mode: rather than extracting features from only documents in the QRels file, new documents will be retrieved and features will be extracted from the retrieved documents only"})
  private boolean retrievalMode = false;

  @Parameters(index = "0", paramLabel = "QRELS",
      description = "path to qrels file from which to extract features")
  private Path qrelsPath;

  @Parameters(index = "1", paramLabel = "OUTPUT-DIRECTORY")
  private Path outputPath;

  @Option(names = {"--limit", "-L"},
  description = "maximum number of topics to consider")
  private int limit = Integer.MAX_VALUE;

  @Option(names = {"-F", "--feature-map"})
  private Path featureMap = null;

  @Override
  public void run() {
    try {
      Files.createDirectories(outputPath);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    // Load qrels
    log.info("Loading qrels...");
    final QRels qrels = QRels.fromFile(qrelsPath);

    // Get trials with gold-linked articles
    log.info("Loading trials...");
    List<JaxbClinicalTrial> trials = Experiments.getTrialsWithPmids(qrels);

    // Sample 100 of them randomly without replacement
    if (limit < trials.size()) {
      log.info("Sampling {} trials randomly without replacement...", limit);
      final Random random = new Random(1337);
      Collections.shuffle(trials, random);
      trials = trials.subList(0, limit);
    }

    // Build NCT Linker
    log.info("Initializing NCT Linker...");
    final NctLinker linker = new NctLinker(trials);

    // Analyze trials
    log.info("Analyzing clinical trials...");
    final AnalyzedNctLinker analyzedLinker = linker.analyzeTrials();

    // Expand trial conditions
    try (UMLSManager umls = new UMLSManager()) {
      final Expander<CharSequence, Weighted<String>> umlsExpander = umls.withFixedWeight(1);
      analyzedLinker.expandInterventions(umlsExpander);
      analyzedLinker.expandConditions(umlsExpander);
    }

    IntIdentifier<String> featureIdentifier;
    if (featureMap == null) {
      log.debug("Creating fresh feature identifier...");
      featureIdentifier = new IntIdentifier<>();
    } else {
      log.info("Loading feature IDs from {}...", featureMap);
      featureIdentifier = IntIdentifier.fromFile(featureMap).lock();
    }

    // Extract L2R feature vectors
    if (retrievalMode) {
      log.info("Extracting L2R features from **RETRIEVED** documents...");
      analyzedLinker.extractFeaturesFromRetrievedDocuments(qrels, outputPath, featureIdentifier);
    } else {
      log.info("Extracting L2R features from **JUDGED** documents...");
      analyzedLinker.extractFeaturesFromJudgedDocuments(qrels, outputPath, featureIdentifier);
    }
    log.info("Done!");
  }

  public static void main(String... args) {
    Commands.run(new ExtractNctL2RFeatures(), args);
  }
}
