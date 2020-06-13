package com.samples.verifier.model

class ParseConfiguration() {
  /**
   * hashset of flags for runnable code snippets (classes for HTML or meta-information for MD)
   */
  var snippetFlags: HashSet<String> = hashSetOf()

  /**
   * hashset of html attributes so tags with them are ignored
   */
  var ignoreAttributes: HashSet<Attribute>? = null

  /**
   * parse directories that match regexp
   */
  var parseDirectory: Regex? = null

  /**
   * ignore directories that match regexp
   */
  var ignoreDirectory: Regex? = null

  /**
   * hashset of html tags to be accepted as code snippets, works for both html and md
   * default (code) for MD so only fencedCodeBlocks are accepted as code snippets
   */
  var parseTags: HashSet<String>? = null

  constructor(block: ParseConfiguration.() -> Unit) : this() {
    this.block()
  }
}

data class Attribute(val name: String, val value: String)