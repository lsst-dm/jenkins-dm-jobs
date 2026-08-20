package lib

// Base script for loading Jenkins pipeline library scripts outside Jenkins.
// Undefined pipeline steps (sh, node, echo, ...) resolve to no-ops so the
// script body can be evaluated; pure helper methods can then be called.
abstract class StepSwallowingScript extends Script {
  def methodMissing(String name, args) { return null }
  def propertyMissing(String name) { return null }
}
