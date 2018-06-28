package edu.utdallas.hltri.nct_link.l2r;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import edu.utdallas.hltri.logging.Logger;
import it.unimi.dsi.fastutil.ints.Int2FloatMap;

import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import edu.utdallas.hltri.data.clinical_trials.AnalyzedTrial;
import edu.utdallas.hltri.data.clinical_trials.JaxbClinicalTrial;
import edu.utdallas.hltri.data.medline.jaxb.JaxbMedlineArticle;
import edu.utdallas.hltri.data.medline.jaxb.JaxbMedlineSearchEngine;
import edu.utdallas.hltri.inquire.eval.QRels;
import edu.utdallas.hltri.inquire.l2r.RankingFeatureExtractor;
import edu.utdallas.hltri.inquire.lucene.LuceneResult;
import edu.utdallas.hltri.inquire.lucene.LuceneSearchEngine;
import edu.utdallas.hltri.inquire.lucene.LuceneSearchResultsList;
import edu.utdallas.hltri.inquire.lucene.similarity.Similarities;
import edu.utdallas.hltri.ml.Extractors;
import edu.utdallas.hltri.ml.Feature;
import edu.utdallas.hltri.ml.MultiFeature;
import edu.utdallas.hltri.ml.NumericFeature;
import edu.utdallas.hltri.nct_link.search.MedlineTrialQueryFactory;
import edu.utdallas.hltri.struct.Pair;
import edu.utdallas.hltri.util.IntIdentifier;

public class NctFeatureExtractor {
  private static final Logger log = Logger.get(NctFeatureExtractor.class);

  private final LuceneSearchEngine<JaxbMedlineArticle> medline;
//  final MedlineTrialQueryFactory queryFactory;

  private final LoadingCache<AnalyzedTrial, LuceneSearchResultsList<JaxbMedlineArticle>>
      resultsCache;

  private final Cache<Pair<Query, Similarities>, Int2FloatMap> scoreCache = CacheBuilder.newBuilder()
      .maximumSize(100)
      .build();

  private final LoadingCache<AnalyzedTrial, MedlineTrialQueryFactory.MedlineTrialQueryBuilder> builderCache;

  public NctFeatureExtractor(
      JaxbMedlineSearchEngine medline,
      MedlineTrialQueryFactory queryFactory,
      LoadingCache<AnalyzedTrial, LuceneSearchResultsList<JaxbMedlineArticle>> resultsCache) {
    this.medline = medline;
//    this.queryFactory = queryFactory;
    this.resultsCache = resultsCache;
    this.builderCache = CacheBuilder.newBuilder()
        .build(CacheLoader.from(trial -> queryFactory.new MedlineTrialQueryBuilder(trial)));
  }

  private BiFunction<AnalyzedTrial, JaxbMedlineArticle, Collection<? extends Feature<Number>>> relevance(
      final String name,
      final Similarities similarity,
      Function<MedlineTrialQueryFactory.MedlineTrialQueryBuilder, Query> queryBuilder) {
    return relevances(name, similarity, queryBuilder.andThen(Collections::singleton));
  }

  private BiFunction<AnalyzedTrial, JaxbMedlineArticle, Collection<? extends Feature<Number>>> relevances(
      final String name,
      final Similarities similarity,
      Function<MedlineTrialQueryFactory.MedlineTrialQueryBuilder, Collection<Query>> queryBuilder) {
    return (AnalyzedTrial trial, JaxbMedlineArticle article) -> {
      final Collection<Query> queries = queryBuilder.apply(builderCache.getUnchecked(trial));
      if (queries.isEmpty()) {
        return Collections.singleton(
            Feature.numericFeature("dynamic.relevance." + name + "." + similarity.name(),
            0f));
      }
      final Collection<Feature<Number>> relevanceScores = new ArrayList<>(queries.size());
      for (final Query query : queries) {
        try {
          final float score = scoreCache.get(Pair.of(query, similarity),
              () -> {
                final BitSet docSet = resultsCache.get(trial).getResults().stream()
                    .mapToInt(LuceneResult::getLuceneDocId)
                    .collect(BitSet::new,
                        BitSet::set,
                        BitSet::or
                    );
                try {
                  return medline.getDocSetScores2(similarity.similarity, query, docSet);
                } catch (IllegalArgumentException e) {
                  log.error("failed to parse", e);
                  System.err.println(query);
                  throw e;
                }
              }).get(article.getLuceneDocId());
          relevanceScores.add(
              Feature.numericFeature("dynamic.relevance." + name + "." + similarity.name(),
                  score));
        } catch (ExecutionException ee) {
          throw new RuntimeException(ee);
        }
      }
      if (relevanceScores.isEmpty()) {
        return Collections.singleton(Feature.numericFeature("dynamic.relevance." + name + "." + similarity.name(), 0f));
      }
      return relevanceScores;
    };
  }

  public Collection<Function<AnalyzedTrial, Collection<? extends Feature<Number>>>> getQueryFeatureExtractors() {
    final Collection<Function<AnalyzedTrial, Collection<? extends Feature<Number>>>> queryFeatures
        = new ArrayList<>();
    queryFeatures.add(Extractors.singleInt("trial.numInvestigators", trial -> trial.getAllInvestigators().size()));
    queryFeatures.add(Extractors.singleLong("trial.numInterventions", trial -> trial.getInterventions().size()));
    queryFeatures.add(Extractors.singleLong("trial.numConditions", trial -> trial.getConditions().size()));
    queryFeatures.add(Extractors.singleLong("trial.date", JaxbClinicalTrial::getDate));
    return queryFeatures;
  }

  public Collection<Function<JaxbMedlineArticle, Collection<? extends Feature<Number>>>> getStaticFeatureExtractors() {
    final Collection<Function<JaxbMedlineArticle, Collection<? extends Feature<Number>>>> documentFunctions
        = new ArrayList<>();
    documentFunctions.add(Extractors.singleInt("article.numAuthors", article -> article.getAuthors().size()));
    documentFunctions.add(Extractors.singleInt("article.numInvestigators", article -> article.getInvestigators().size()));
    documentFunctions.add(Extractors.singleInt("article.date", article -> article.getCreationDate().getYear()));
    documentFunctions.add(article -> new MultiFeature<>("article.publicationTypes", article.getPublicationTypes()).stream()
        .map(Feature::toNumericFeature)
        .collect(Collectors.toList()));

    // Adds way too many features!
//    documentFunctions.add(article -> Collections.singleton(Feature.stringFeature("article.journal", article.getJournalTitle()).toNumericFeature()));
    return documentFunctions;
  }

  public Collection<BiFunction<AnalyzedTrial, JaxbMedlineArticle, Collection<? extends Feature<Number>>>> getDynamicFeatureExtractors() {
    return getDynamicFeatureExtractors(EnumSet.of(
        Similarities.BM25,
        Similarities.LMD,
        Similarities.F2EXP,
        Similarities.DFI
    ));
  }

  public Collection<BiFunction<AnalyzedTrial, JaxbMedlineArticle, Collection<? extends Feature<Number>>>> getDynamicFeatureExtractors(
      EnumSet<Similarities> similarities
  ) {
    final Collection<BiFunction<AnalyzedTrial, JaxbMedlineArticle, Collection<? extends Feature<Number>>>> dynamicFunctions =
        new ArrayList<>();

    for (Similarities sim : similarities) {
      dynamicFunctions.add(relevance("dynamic.nctId", sim, b -> b.getNctIdQuery()));
      dynamicFunctions.add(relevance("dynamic.keywords", sim, b -> b.getKeywordsQuery().orElse(new MatchNoDocsQuery())));
      dynamicFunctions.add(relevance("dynamic.mesh", sim, b -> b.getMeshTermsQuery().orElse(new MatchNoDocsQuery())));
      dynamicFunctions.add(relevance("dynamic.joint", sim, b -> b.build()));


      dynamicFunctions.add(
          relevances("dynamic.investigators", sim, b -> b.getInvestigatorsQueries())
              .andThen(Feature::flatten)
              .andThen(NumericFeature::getStatistics));

      dynamicFunctions.add(
          relevances("dynamic.conditions", sim, b -> b.getConditionsQueries())
              .andThen(Feature::flatten)
              .andThen(NumericFeature::getStatistics));

      dynamicFunctions.add(
          relevances("dynamic.interventions", sim, b -> b.getInterventionsQueries())
              .andThen(Feature::flatten)
              .andThen(NumericFeature::getStatistics));
    }

    dynamicFunctions.add((trial, article) -> Collections.singleton(
        Feature.numericFeature("dynamic.yearDifference",
            (trial.getDate() - article.getCreationDate().toEpochDay()) / 365f)));

    return dynamicFunctions;
  }

  public void extract(final Collection<AnalyzedTrial> queries,
                      final QRels judgments,
                      final Path outputPath,
                      final IntIdentifier<String> featureIdentifier) {
    final RankingFeatureExtractor<AnalyzedTrial, JaxbMedlineArticle> rfe =
        new RankingFeatureExtractor<>(
            getQueryFeatureExtractors(),
            getStaticFeatureExtractors(),
            getDynamicFeatureExtractors(),
            (trial, article) -> judgments.getRelevance(trial.getNctId(), article.getPubmedId()).toInt()
        );

    rfe.vectorize(featureIdentifier,
        queries,
        trial -> resultsCache.getUnchecked(trial).getResults().stream().map(LuceneResult::getValue).collect(Collectors.toList()),
        trial -> {
          builderCache.invalidate(trial);
          resultsCache.invalidateAll();
          scoreCache.invalidateAll();
        },
        outputPath.resolve("vectors.smr"),
        outputPath.resolve("feature_map.tsv"),
        outputPath.resolve("trial_id_map.tsv")
    );
    rfe.saveMappedQrels(judgments, outputPath.resolve("mapped_qrels.txt"));
  }
}
