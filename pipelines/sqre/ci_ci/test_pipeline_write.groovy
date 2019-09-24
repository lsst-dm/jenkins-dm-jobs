node {
  dir('jenkins-dm-jobs') {
    checkout([
      $class: 'GitSCM',
      branches: scm.getBranches(),
      userRemoteConfigs: scm.getUserRemoteConfigs(),
      changelog: false,
      poll: false
    ])
    notify = load 'pipelines/lib/notify.groovy'
    util = load 'pipelines/lib/util.groovy'
  }
}

notify.wrap {
  stage('write json') {
    util.nodeTiny {
      def results = [
        foo: 'bar',
        baz: 'quix',
      ]

      dumpJson('results.json', results)

      archiveArtifacts([
        artifacts: 'results.json',
        fingerprint: true
      ])
    }
  }
} // notify.wrap

@NonCPS
def dumpJson(String filename, Map data) {
  def json = new groovy.json.JsonBuilder(data)
  def pretty = groovy.json.JsonOutput.prettyPrint(json.toString())
  echo pretty
  writeFile file: filename, text: pretty
}
