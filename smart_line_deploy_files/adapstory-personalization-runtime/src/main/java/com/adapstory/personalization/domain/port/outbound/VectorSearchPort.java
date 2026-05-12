package com.adapstory.personalization.domain.port.outbound;

import java.util.List;

/**
 * Порт для поиска по векторной базе знаний (Qdrant).
 * Аналог retrieve_context() из ИИ_агент.ipynb.
 */
public interface VectorSearchPort {

  /**
   * Найти релевантные фрагменты по текстовому запросу.
   *
   * @param query  текстовый запрос студента
   * @param topK   количество результатов
   * @return       список контекстных фрагментов
   */
  List<ContextChunk> search(String query, int topK);

  /**
   * Фрагмент контекста из базы знаний.
   */
  record ContextChunk(
      String text,
      Integer page,
      String source,
      double score
  ) {}
}
