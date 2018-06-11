node('jenkins-master') {
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

String mkBaseName(Integer majrelease) {
  "${majrelease}-stackbase"
}

// This job is intentionally not using parallel branches so that updates to the
// set of ( stackbase X compiler scls ) images is atomic-ish.  As the total
// build time is well under 15mins, this seems like a tolerable trade-off
// against performance. Note that this could also be achieved by artifact-ing
// each image from a parallel branch and then doing the push all at once at the
// cost of having to storage large artifacts within jenkins.
notify.wrap {
  util.requireParams([
    'NO_PUSH',
  ])

  Boolean pushDocker = (! params.NO_PUSH.toBoolean())

  def run = {
    def baseRepo  = 'centos'
    def buildRepo = 'lsstsqre/centos'

    git([
      url: 'https://github.com/lsst-sqre/packer-layercake.git',
      branch: 'master',
      changelog: false,
      poll: false
    ])

    def images = []

    // centos major release version(s)
    [6, 7].each { majrelease ->
      def baseImage = "${baseRepo}:${majrelease}"
      def baseName = mkBaseName(majrelease)
      def baseTag = "${buildRepo}:${baseName}"

      stage(baseTag) {
        util.librarianPuppet()
        def baseBuild = packIt('centos_stackbase.json', [
          "-var base_image=${baseImage}",
          "-var build_name=${baseName}",
        ])
        images << [(baseTag): baseBuild]
      } // stage
    } // majrelease

    // scl compiler string(s)
    [
      [(mkBaseName(6)): 'devtoolset-6'],
      [(mkBaseName(7)): 'devtoolset-6'],
      [(mkBaseName(6)): 'devtoolset-7'],
      [(mkBaseName(7)): 'devtoolset-7'],
      [(mkBaseName(7)): 'llvm-toolset-7'],
    ].each { conf ->
      conf.each { baseName, scl ->
        def baseTag = "${buildRepo}:${baseName}"
        def tsName = "${baseName}-${scl}"
        def tsTag = "${buildRepo}:${tsName}"

        stage(tsTag) {
          tsBuild = packIt('centos_devtoolset.json', [
            "-var base_image=${baseTag}",
            "-var build_name=${tsName}",
            "-var scl_compiler=${scl}",
          ])
          images << [(tsTag): tsBuild]
        } // stage
      } // baseName, scl
    } // conf

    stage('push') {
      if (pushDocker) {
        images.each { item ->
          item.each { tag, build ->
            shipIt(build, tag)
          }
        }
      }
    } // stage
  } // run

  timeout(time: 23, unit: 'HOURS') {
    node('docker') {
      timeout(time: 30, unit: 'MINUTES') {
        run()
      }
    }
  }
} // notify.wrap

def String packIt(String templateFile, List options, String tag = '1.1.1') {
  def dockerSetup = '-v /var/run/docker.sock:/var/run/docker.sock'
  dockerSetup     = "-e HOME=${pwd()} ${dockerSetup}"
  def docImage    = "lsstsqre/cakepacker:${tag}"
  def args        = options.join(' ')

  docker.image(docImage).inside(dockerSetup) {
    // alpine does not include bash by default
    util.posixSh "packer build ${args} ${templateFile}"
  }

  def manifest = readJSON file: 'packer-manifest.json'
  def last = manifest['last_run_uuid']
  def build = manifest['builds'].findResult {
    it['packer_run_uuid'] == last ? it : null
  }

  // this is only needed to get jenkins to store a docker fingerprint
  docker.image(build['artifact_id'])

  build
}

def void shipIt(Map build, String tag) {
  def sha2  = build['artifact_id']
  def timestamp = util.epochToUtc((build['build_time']))

  // push tag AND tag+utc
  [tag, "${tag}-${timestamp}"].each { name ->
    tagIt(sha2, name)
    pushIt(name)
  }
}

// image.push() screws up with docker.image("sha:...")
def void tagIt(String id, String tag) {
  util.bash "docker tag ${id} ${tag}"
}

def void pushIt(String tag) {
  docker.withRegistry(
    'https://index.docker.io/v1/',
    'dockerhub-sqreadmin'
  ) {
    util.bash "docker push ${tag}"
  }
}
