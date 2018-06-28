package edu.utdallas.hltri.nct_link.scripts;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;
import edu.utdallas.hlt.medbase.umls.UMLSManager;
import edu.utdallas.hltri.data.clinical_trials.AnalyzedTrial;
import edu.utdallas.hltri.data.clinical_trials.ClinicalTrial;
import edu.utdallas.hltri.data.clinical_trials.JaxbClinicalTrial;
import edu.utdallas.hltri.data.medline.MedlineArticle;
import edu.utdallas.hltri.data.medline.MedlineSearchEngine;
import edu.utdallas.hltri.data.medline.jaxb.JaxbMedlineArticle;
import edu.utdallas.hltri.framework.Commands;
import edu.utdallas.hltri.framework.ProgressLogger;
import edu.utdallas.hltri.inquire.eval.QRels;
import edu.utdallas.hltri.inquire.eval.QRels.Relevance;
import edu.utdallas.hltri.inquire.lucene.LuceneSearchResultsList;
import edu.utdallas.hltri.io.IOUtils;
import edu.utdallas.hltri.logging.Logger;
import edu.utdallas.hltri.nct_link.Experiments;
import edu.utdallas.hltri.nct_link.NctLinker;
import edu.utdallas.hltri.nct_link.NctLinker.AnalyzedNctLinker;
import edu.utdallas.hltri.nct_link.search.MedlineTrialQueryFactory;
import edu.utdallas.hltri.struct.Weighted;
import edu.utdallas.hltri.util.Expander;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

public class AddSearchResultsToQRels implements Runnable {
  @Option(names = "-n, ---num-non-relevant",
          description = "number of non-relevant documents to add to QRels file for each topic")
  private int numNegatives = 10;

  @Parameters(index = "0",
      paramLabel = "IN-QRELS-PATH")
  private Path inputQrelsPath;

  private SetMultimap<String, String> writtenPmids = HashMultimap.create();

  private static final Logger log = Logger.get(AddSearchResultsToQRels.class);

  private void writeExistingQrels(String nctId, QRels inputQRels, BufferedWriter writer) throws IOException {
    for (Entry<String, Relevance> entry : inputQRels.getJudgements(nctId).entrySet()) {
      if (entry.getValue() != Relevance.UNKNOWN) {
        writer.append(nctId)
            .append(" 0 ")
            .append(entry.getKey())
            .append(' ')
            .append(Integer.toString(entry.getValue().toInt()));
        writer.newLine();
        writtenPmids.put(nctId, entry.getKey());
      }
    }
  }

  private int writeGoldNonrelevantPmids(final String nctId,
      final LuceneSearchResultsList<? extends MedlineArticle> results,
      final QRels inputQRels,
      final BufferedWriter writer) throws IOException {
    final Set<String> pmids = results.getResults()
        .stream()
        .map(lr -> lr.getValue().getPubmedId())
        .collect(Collectors.toSet());
    int numNonRelevantPmids = 0;
    for (Iterator<String> it = pmids.iterator();
        it.hasNext() && numNonRelevantPmids < numNegatives; ) {
      String pmid = it.next();
      if (inputQRels.getRelevance(nctId, pmid) == Relevance.UNKNOWN) {
        final Set<String> trialsWithPmid = inputQRels.getTopicsWithDocument(pmid);
        if (!trialsWithPmid.contains(nctId) && !trialsWithPmid.isEmpty() && !writtenPmids.containsEntry(nctId, pmid)) {
          writtenPmids.put(nctId, pmid);
          writer.append(nctId)
              .append(" 0 ")
              .append(pmid)
              .append(" 0");
          writer.newLine();
          numNonRelevantPmids++;
        }
      }
    }
    log.debug("Found {} gold-non-relevant articles for {}", numNonRelevantPmids, nctId);
    return numNonRelevantPmids;
  }

  private int writeSilverNonrelevantPmids(final String nctId,
      final LuceneSearchResultsList<? extends MedlineArticle> results,
      int number,
      final Random random,
      int start,
      int end,
      final BufferedWriter writer) throws IOException {
    if (results.getResults().size() < start) {
      log.debug("Found {} silver-non-relevant ({} > size) articles for {}", 0, start, nctId);
      return 0;
    }
    // Update end if it is > num results
    end = Math.min(results.getResults().size(), end);
    if (start >= end) {
      log.debug("Found {} silver-non-relevant ({} > {}) articles for {}", 0, start, end, nctId);
      return 0;
    }
    List<Integer> indices = IntStream.range(start, end).boxed().collect(Collectors.toList());
    Collections.shuffle(indices, random);
    int written = 0;
    for (int i = 0; written < number && i < indices.size(); i++) {
      int randIndex= indices.get(i);
      final String randPmid = results.getResults().get(randIndex).getValue().getPubmedId();
      if (!writtenPmids.containsEntry(nctId, randPmid)) {
        writer.append(nctId)
            .append(" 0 ")
            .append(randPmid)
            .append(" 0");
        writer.newLine();
        writtenPmids.put(nctId, randPmid);
        written ++;
      }
    }

    log.debug("Found {} silver-non-relevant ({}-{}) articles for {}", written, start, end, nctId);
    return written;
  }

  public int writeRandomPmid(String nctId,
      List<? extends ClinicalTrial> trials,
      Random random,
      QRels inputQRels,
      int number,
      BufferedWriter writer) throws IOException {
    int written = 0;
    while (written < number) {
      final String randomTrial = trials.get(random.nextInt(trials.size())).getNctId();
      if (randomTrial.equals(nctId)) {
        continue;
      }
      final List<String> judgedArticles = inputQRels.getJudgements(randomTrial).entrySet().stream()
          .filter(e -> e.getValue().toInt() > 0)
          .map(Entry::getKey)
          .collect(Collectors.toList());
      final String randomArticle = judgedArticles.get(random.nextInt(judgedArticles.size()));

      if (!writtenPmids.containsEntry(nctId, randomArticle)) {
        writer.append(nctId)
            .append(" 0 ")
            .append(randomArticle)
            .append(" 0");
        writer.newLine();
        written++;
        writtenPmids.put(nctId, randomArticle);
      }
    }
    return written;
  }

  public void run() {
    for (String qrelsFile : new String[]{"qrels.train.txt", "qrels.devel.txt", "qrels.test.txt"}) {
      final QRels inputQRels = QRels.fromFile(inputQrelsPath.resolve(qrelsFile));
      final List<JaxbClinicalTrial> trials = Experiments.getTrialsWithPmids(inputQRels);
      final AnalyzedNctLinker linker = new NctLinker(trials).analyzeTrials();
      try (UMLSManager umls = new UMLSManager()) {
        final Expander<CharSequence, Weighted<String>> umlsExpander = umls.withFixedWeight(1);
        linker.expandInterventions(umlsExpander);
        linker.expandConditions(umlsExpander);
      }

      final Random random = new Random(1337);

      try (BufferedWriter writer = Files.newBufferedWriter(inputQrelsPath.resolve(
          IOUtils.removeExtension(qrelsFile) + ".full.txt"))) {
        final MedlineTrialQueryFactory factory = new MedlineTrialQueryFactory();
        try (MedlineSearchEngine<JaxbMedlineArticle> medline = MedlineSearchEngine.getJaxbLazy();
            ProgressLogger plog = ProgressLogger.fixedSize("processing",
                linker.getTrials().size(),
                1,
                TimeUnit.MINUTES)) {
          for (AnalyzedTrial trial : linker.getTrials()) {
            final String nctId = trial.getNctId();
            writeExistingQrels(nctId, inputQRels, writer);

            final LuceneSearchResultsList<JaxbMedlineArticle> searchResults =
                medline.search(factory.getQuery(trial), 3_000);

            int numNonRelevantPmids = writeGoldNonrelevantPmids(nctId,
                searchResults,
                inputQRels,
                writer);

            numNonRelevantPmids += writeSilverNonrelevantPmids(nctId,
                searchResults,
                numNegatives,
                random,
                10, 100,
                writer);

            numNonRelevantPmids += writeSilverNonrelevantPmids(nctId,
                searchResults,
                numNegatives,
                random,
                1000, 2000,
                writer);

            numNonRelevantPmids += writeSilverNonrelevantPmids(nctId,
                searchResults,
                numNegatives,
                random,
                2000, 3000,
                writer);

            numNonRelevantPmids += writeRandomPmid(nctId, trials, random, inputQRels, 10, writer);

            plog.update("found {} non-relevant for {}", numNonRelevantPmids, nctId);
          }
        }
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  public static void main(String... args) {
    Commands.run(new AddSearchResultsToQRels(), args);
  }
}
