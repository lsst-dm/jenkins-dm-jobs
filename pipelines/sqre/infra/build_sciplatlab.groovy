import java.time.Instant

node('jenkins-manager') {
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
    scipipe = util.scipipeConfig() // needed for side effects
    sqre = util.sqreConfig() // needed for side effects
  }
}

notify.wrap {
  util.requireParams([
    'TAG',
    'IMAGE',
    'SUPPLEMENTARY',
    'BRANCH',
  ])

  String tag              = params.TAG
  String supplementary    = params.SUPPLEMENTARY
  String image            = params.IMAGE
  String branch           = params.BRANCH
  Instant starttime = null

  def run = {
    stage('trigger GHA') {
      def body = [
        ref: branch,
        inputs: [
          tag: tag,
          supplementary: supplementary,
          image: image,
        ]
      ]
      def json = new groovy.json.JsonBuilder(body)

      def url = new URL("https://api.github.com/repos/lsst-sqre/sciplat-lab/actions/workflows/build.yaml/dispatches")
      starttime = Instant.now()
      println("url: ${url}")
      println("body: ${json}")
      println("starttime: {$starttime}")

      def conn = url.openConnection().with { conn ->
        conn.setRequestMethod('POST')
        conn.setRequestProperty('Content-Type', 'application/json; charset=utf-8')
        conn.setRequestProperty('Accept', 'application/vnd.github.v3+json')
        conn.setRequestProperty('Authorization', "Bearer ${GITHUB_TOKEN}")
        conn.doOutput = true
        conn.outputStream << json.toPrettyString()

        assert (conn.responseCode == 204) : "API dispatch failed: ${conn.responseCode}|${conn.getInputStream().getText()}"
      } // openConnection().with
    } // stage

    stage('poll for job completion at GHA') {
      // We presume that GH Actions will continue to list most recent runs
      // at the top.  Poll the build.yaml runs for the first result.

      def url = new URL("https://api.github.com/repos/lsst-sqre/sciplat-lab/actions/workflows/build.yaml/runs?per_page=1&branch=${branch}")

      def created_at = starttime.minusSeconds(1)
      // One second before we really started the action.
      // Note that we're assuming our local clock and GitHub's are pretty
      // well synchronized.  If this assumption is violated, we probably
      // have worse problems than this build failing.
      //
      // We need the minus one second because GitHub only reports the time
      // with a granularity of one second, and it's possible, even likely,
      // that the POST completes in one second; if the POST happens at x.2
      // and the workflow run starts at x.8, the time-from-GitHub reply will
      // look like it's at x.0, before the POST.
      //
      // Note also that the job we will find is the first one that shows up
      // that was started after T minus 1, so there is a chance that we
      // might be latching on to the wrong job if multiple jobs were
      // submitted in the same second.  Since there is no data returned from
      // the POST that would identify the job the POST ran, I think this is
      // the best we can do.

      println("Initial polling loop to find run_id:  branch: ${branch}; created_at: ${created_at}; url: ${url}; starttime: ${starttime}")

      def loop_idx = 0
      def jsonSlurper = new groovy.json.JsonSlurper()
      def run_id = 0
      def wf = {}
      def status = ""
      def conclusion = ""
      while ((run_id == 0) || (created_at < starttime)) {
        if (loop_idx > 30) {
          assert 0: "Build did not start in 15 minutes: ${status}/${conclusion}"
        }
        if (loop_idx != 0) {
          Thread.sleep(30 * 1000)  // wait 30 secs second/subsequent loops
        }
        loop_idx += 1
        def conn = url.openConnection().with { conn ->
          conn.setRequestMethod('GET')
          conn.setRequestProperty('Content-Type', 'application/json; charset=utf-8')
          conn.setRequestProperty('Accept', 'application/vnd.github.v3+json')
          conn.setRequestProperty('Authorization', "Bearer ${GITHUB_TOKEN}")
          def text = conn.getInputStream().getText()
          def obj = jsonSlurper.parseText(text)
          wf = obj.workflow_runs[0]
          run_id = wf.id
          created_at = Instant.parse(wf.created_at)
          println("#{$loop_idx}: id = ${run_id}; created_at=${created_at}")
        } // openConnection().with
      } // while
      assert (run_id != 0): "Did not find run_id!"
      loop_idx = 0
      status = wf.status
      conclusion = wf.conclusion
      wf = {}
      // Next loop: we have a run_id, so we can check its status directly
      url = new URL("https://api.github.com/repos/lsst-sqre/sciplat-lab/actions/runs/${run_id}")
      while ( status != "completed") {
        if (loop_idx > 240) {
          assert 0: "Build did not finish in 2 hours: ${status}/${conclusion}"
        }
        if (loop_idx > 0) {
          Thread.sleep(30 * 1000)  // wait 30 secs second/subsequent loops
        }
        loop_idx += 1
        def conn = url.openConnection().with { conn ->
          conn.setRequestMethod('GET')
          conn.setRequestProperty('Content-Type', 'application/json; charset=utf-8')
          conn.setRequestProperty('Accept', 'application/vnd.github.v3+json')
          conn.setRequestProperty('Authorization', "Bearer ${GITHUB_TOKEN}")
          def text = conn.getInputStream().getText()
          wf = jsonSlurper.parseText(text)
          status = wf.status
          conclusion = wf.conclusion
          println("#{$loop_idx}: id = ${run_id}; status=${status}; conclusion=${conclusion}")
        } // openConnection.with()
      } // while
      if (conclusion != "success") {
          currentBuild.result = 'FAILURE'
      }
      assert conclusion == "success": "Build for id ${run_id} failed: conclusion=${conclusion}"
    } // stage
  } // run

  util.withGithubAdminCredentials {
    run()
  }
} // notify.wrap
