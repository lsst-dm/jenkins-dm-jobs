echo "branch: ${BRANCH}"
echo "product: ${PRODUCT}"
echo "tag: ${TAG}"


stage 'build'

def result = build job: 'run-rebuild',
    parameters: [
      string(name: 'BRANCH', value: BRANCH),
      string(name: 'PRODUCT', value: PRODUCT),
      booleanParam(name: 'SKIP_DEMO', value: false),
      booleanParam(name: 'SKIP_DOCS', value: false)
    ],
    wait: true
def jenkins_id = result.id


stage 'parse bNNNN'

node {
  step ([$class: 'CopyArtifact',
        projectName: 'run-rebuild',
        filter: 'build/manifest.txt',
        selector: [$class: 'SpecificBuildSelector', buildNumber: jenkins_id]
        ]);

  def manifest = readFile 'build/manifest.txt'
  build_id = build_id(manifest)

  echo "parsed build_id: ${build_id}"
}


stage 'eups tag'

build job: 'run-publish',
  parameters: [
    string(name: 'EUPSPKG_SOURCE', value: 'git'),
    string(name: 'BUILD_ID', value: build_id),
    string(name: 'TAG', value: TAG),
    string(name: 'PRODUCTS', value: PRODUCT)
  ]


stage 'git tag'

build job: 'release/tag-git-repos',
  parameters: [
    string(name: 'BUILD_ID', value: build_id),
    string(name: 'TAG', value: TAG)
  ]


@NonCPS

def build_id(manifest) {
  def m = manifest =~ /(?m)^BUILD=(.*)/
  m ? m[0][1] : null
}
