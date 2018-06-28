package edu.utdallas.hltri.nct_link;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import edu.utdallas.hltri.data.medline.jaxb.JaxbMedlineSearchEngine;
import edu.utdallas.hltri.inquire.eval.QRels.Relevance;
import edu.utdallas.hltri.inquire.lucene.LuceneSearchResultsList;
import edu.utdallas.hltri.trec.pm.Expandable;
import edu.utdallas.hltri.util.IntIdentifier;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import org.apache.lucene.search.Query;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import edu.utdallas.hltri.data.clinical_trials.AnalyzedTrial;
import edu.utdallas.hltri.data.clinical_trials.JaxbClinicalTrial;
import edu.utdallas.hltri.data.medline.MedlineSearchEngine;
import edu.utdallas.hltri.data.medline.jaxb.JaxbMedlineArticle;
import edu.utdallas.hltri.framework.ProgressLogger;
import edu.utdallas.hltri.inquire.SearchResult;
import edu.utdallas.hltri.inquire.engines.SearchResults;
import edu.utdallas.hltri.inquire.eval.QRels;
import edu.utdallas.hltri.inquire.eval.TrecRunWriter;
import edu.utdallas.hltri.inquire.lucene.LuceneSearchEngine;
import edu.utdallas.hltri.nct_link.l2r.NctFeatureExtractor;
import edu.utdallas.hltri.nct_link.search.MedlineTrialQueryFactory;
import edu.utdallas.hltri.struct.Weighted;
import edu.utdallas.hltri.trec.pm.MedicalProblem;
import edu.utdallas.hltri.util.Expander;

public class NctLinker {
  private final Collection<JaxbClinicalTrial> trials;

  public NctLinker(Collection<JaxbClinicalTrial> trials) {
    this.trials = trials;
  }

  public AnalyzedNctLinker analyzeTrials() {
    return analyzeTrials(new ClinicalTrialAnalyzer());
  }

  public AnalyzedNctLinker analyzeTrials(ClinicalTrialAnalyzer analyzer) {
    final List<AnalyzedTrial> analyzedTrials = new ArrayList<>();
    try(ProgressLogger plog = ProgressLogger.fixedSize("analyzing trials",
        trials.size(),
      1, TimeUnit.MINUTES)) {
      for (JaxbClinicalTrial trial : trials) {
        analyzedTrials.add(analyzer.analyze(trial));
        plog.update("analyzed {}", trial.getNctId());
      }
    }
    return new AnalyzedNctLinker(analyzedTrials);
  }

  public static class AnalyzedNctLinker {
    private final List<AnalyzedTrial> trials;

    public AnalyzedNctLinker(List<AnalyzedTrial> trials) {
      this.trials = trials;
    }

    public AnalyzedNctLinker expandConditions(Expander<? super MedicalProblem, Weighted<String>> expander) {
      try (ProgressLogger plog = ProgressLogger.fixedSize("expanding",
          trials.size(),
          1, TimeUnit.MINUTES)) {
        for (AnalyzedTrial trial : trials) {
          for (MedicalProblem condition : trial.getAnalyzedConditions()) {
            condition.addExpansions(expander.expand(condition));
          }
          plog.update("expanded {}", trial.getNctId());
        }
      }
      return this;
    }

    public AnalyzedNctLinker expandInterventions(Expander<? super Expandable, Weighted<String>> expander) {
      try (ProgressLogger plog = ProgressLogger.fixedSize("expanding",
          trials.size(),
          1, TimeUnit.MINUTES)) {
        for (AnalyzedTrial trial : trials) {
          for (Expandable intervention : trial.getAnalyzedInterventions()) {
            intervention.addExpansions(expander.expand(intervention));
          }
          plog.update("expanded {}", trial.getNctId());
        }
      }
      return this;
    }


    private void extractL2RFeatures(QRels judgments,
        Path outputPath,
        MedlineTrialQueryFactory queryFactory,
        BiFunction<AnalyzedTrial, JaxbMedlineSearchEngine, LuceneSearchResultsList<JaxbMedlineArticle>> documentRetrievalFunction,
        IntIdentifier<String> featureIdentifier) {
      try (JaxbMedlineSearchEngine medline = MedlineSearchEngine.getJaxbEager()) {
        final NctFeatureExtractor l2rfe = new NctFeatureExtractor(
            medline,
            queryFactory,
            CacheBuilder.<AnalyzedTrial, LuceneSearchResultsList<JaxbMedlineArticle>>newBuilder()
                .maximumSize(100)
                .build(CacheLoader.from(trial -> documentRetrievalFunction.apply(trial, medline)))
        );
        l2rfe.extract(trials, judgments, outputPath, featureIdentifier);
      }
    }


    public void extractFeaturesFromJudgedDocuments(QRels judgments, Path outputPath,
        IntIdentifier<String> featureIdentifier) {
      extractL2RFeatures(
          judgments,
          outputPath,
          new MedlineTrialQueryFactory(),
          (trial, medline) -> {
            final List<String> judgedPmids = judgments.getJudgements(trial.getNctId()).entrySet()
                .stream()
                .filter(e -> e.getValue() != Relevance.UNKNOWN)
                .map(Entry::getKey)
                .collect(Collectors.toList());
            return LuceneSearchResultsList.wrapToList(medline.loadArticlesByPmids(judgedPmids),
                d -> 0,
                d -> 0d,
                JaxbMedlineArticle::getLuceneDocId);
          },
          featureIdentifier
      );
    }

    public void extractFeaturesFromRetrievedDocuments(QRels judgments, Path outputPath, IntIdentifier<String> featureIdentifier) {
      final MedlineTrialQueryFactory queryFactory = new MedlineTrialQueryFactory();
      extractL2RFeatures(
          judgments,
          outputPath,
          queryFactory,
          (trial, medline) -> medline.search(queryFactory.getQuery(trial), 1_000),
          featureIdentifier
      );
    }
    
    public RetrievedNctLinker search() {
      try (LuceneSearchEngine<JaxbMedlineArticle> medlineSearchEngine = MedlineSearchEngine.getJaxbLazy()) {
        return search(new MedlineTrialQueryFactory()::getQuery, medlineSearchEngine);
      }
    }

    public RetrievedNctLinker search(Function<AnalyzedTrial, Query> queryFactory, LuceneSearchEngine<JaxbMedlineArticle> engine) {
      final Map<AnalyzedTrial, Query> luceneQueries = new HashMap<>();
            try (ProgressLogger plog = ProgressLogger.fixedSize("generating queries", trials.size(),
          1, TimeUnit.MINUTES)) {
        for (AnalyzedTrial trial : trials) {
          final Query luceneQuery = queryFactory.apply(trial);
          luceneQueries.put(trial, luceneQuery);
          plog.update("querified {}", trial.getNctId());
        }
      }

      final Map<AnalyzedTrial, SearchResults<JaxbMedlineArticle, ?>> luceneResults = new HashMap<>();
      try (ProgressLogger plog =
          ProgressLogger.fixedSize("retrieving queries", trials.size(),
          1, TimeUnit.MINUTES)) {
        for (AnalyzedTrial trial : trials) {
          final Query luceneQuery = luceneQueries.get(trial);
          final SearchResults<JaxbMedlineArticle, ?> luceneQueryResults =
              engine.search(luceneQuery, 200);
          luceneResults.put(trial, luceneQueryResults);
          plog.update("retrieved {}", trial.getNctId());
        }
      }
      return new RetrievedNctLinker(trials, luceneQueries, luceneResults);
    }

    public List<AnalyzedTrial> getTrials() {
      return trials;
    }
  }

  public static class RetrievedNctLinker extends AnalyzedNctLinker {
    private final Map<AnalyzedTrial, Query> luceneQueries;
    private final Map<AnalyzedTrial, SearchResults<JaxbMedlineArticle, ?>> luceneResults;

    public RetrievedNctLinker(List<AnalyzedTrial> trials,
        Map<AnalyzedTrial, Query> luceneQueries,
        Map<AnalyzedTrial, SearchResults<JaxbMedlineArticle, ?>> luceneResults) {
      super(trials);
      this.luceneQueries = luceneQueries;
      this.luceneResults = luceneResults;
    }

    public RetrievedNctLinker writeAsTrecRun(final Path path) {
      try (final ProgressLogger plog = ProgressLogger.fixedSize("writing",
          luceneQueries.keySet().size(),
          1, TimeUnit.MINUTES);
          final TrecRunWriter writer = new TrecRunWriter(path, "NCTLINK")) {
        for (final Entry<AnalyzedTrial, SearchResults<JaxbMedlineArticle,?>> entry : luceneResults.entrySet()) {
          final AnalyzedTrial trial = entry.getKey();
          final SearchResults<JaxbMedlineArticle, ?> results = entry.getValue();
          for (SearchResult<JaxbMedlineArticle> result : results.getResults()) {
            writer.writeResult(
                trial.getNctId(),
                result.getValue().getPubmedId(),
                result.getRank(),
                result.getScore());
          }
          plog.update("wrote {}", trial.getNctId());
        }
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      return this;
    }
  }
}
