package lib

import spock.lang.Specification

class NotifyThreadContextSpec extends Specification {
  def notify = PipelineScriptLoader.load('pipelines/lib/notify.groovy')

  def "no threadTs leaves the message untouched"() {
    given:
    def msg = [channel: 'c', attachments: []]

    when:
    def out = notify.addThreadContext(msg, null, false)

    then:
    !out.containsKey('thread_ts')
    !out.containsKey('reply_broadcast')
  }

  def "threadTs without broadcast sets only thread_ts"() {
    given:
    def msg = [channel: 'c', attachments: []]

    when:
    def out = notify.addThreadContext(msg, '123.456', false)

    then:
    out.thread_ts == '123.456'
    !out.containsKey('reply_broadcast')
  }

  def "threadTs with broadcast sets thread_ts and reply_broadcast"() {
    given:
    def msg = [channel: 'c', attachments: []]

    when:
    def out = notify.addThreadContext(msg, '123.456', true)

    then:
    out.thread_ts == '123.456'
    out.reply_broadcast == true
  }

  def "broadcast is ignored when threadTs is null"() {
    given:
    def msg = [channel: 'c', attachments: []]

    when:
    def out = notify.addThreadContext(msg, null, true)

    then:
    !out.containsKey('thread_ts')
    !out.containsKey('reply_broadcast')
  }

  def "broadcast defaults to false when omitted"() {
    given:
    def msg = [channel: 'c', attachments: []]

    when:
    def out = notify.addThreadContext(msg, '123.456')

    then:
    out.thread_ts == '123.456'
    !out.containsKey('reply_broadcast')
  }

  def "mutates and returns the same message, preserving existing keys"() {
    given:
    def msg = [channel: 'c', attachments: []]

    when:
    def out = notify.addThreadContext(msg, '123.456', true)

    then:
    out.is(msg)
    out.channel == 'c'
    out.attachments == []
  }
}
