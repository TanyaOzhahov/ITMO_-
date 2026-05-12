package com.adapstory.personalization.domain.model;

/**
 * Тип сообщения студента в рамках обучающего диалога
 * (из ИИ_агент.ipynb IntentClassifier).
 */
public enum StudentMessageIntent {
  ANSWER,
  CHAT,
  QUESTION,
  OFF_TOPIC
}
