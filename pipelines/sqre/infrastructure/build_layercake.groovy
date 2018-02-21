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

notify.wrap {
  def run = {
    git([
      url: 'https://github.com/lsst-sqre/packer-layercake.git',
      branch: 'master',
      changelog: false,
      poll: false
    ])

    def images = []
    def buildRepo = 'lsstsqre/centos'

    // centos major release version(s)
    [6, 7].each { majrelease ->
      def baseName = "${majrelease}-stackbase"
      def baseTag = "${buildRepo}:${baseName}"

      librarianPuppet()
      def baseBuild = packIt('centos_stackbase.json', [
        "-var base_image=centos:${majrelease}",
        "-var build_name=${baseName}",
      ])
      images << [(baseTag): baseBuild]

      // devtoolset version(s)
      [3, 6, 7].each { tsVersion ->
        def tsName = "${baseName}-devtoolset-${tsVersion}"
        def tsTag = "${buildRepo}:${tsName}"

        tsBuild = packIt('centos_devtoolset.json', [
          "-var base_image=${baseTag}",
          "-var build_name=${tsName}",
          "-var version=${tsVersion}",
        ])
        images << [(tsTag): tsBuild]

      } // tsVersion
    } // majrelease

    if (! params.NO_PUSH) {
      images.each { item ->
        item.each { tag, build ->
          shipIt(build, tag)
        }
      }
    }
  } // run

  node('docker') {
    run()
  }
} // notify.wrap

def void librarianPuppet(String cmd='install', String tag='2.2.3') {
  util.insideWrap("lsstsqre/cakepan:${tag}", "-e HOME=${pwd()}") {
    util.bash "librarian-puppet ${cmd}"
  }
}

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
