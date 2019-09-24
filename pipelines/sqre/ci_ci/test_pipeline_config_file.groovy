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

    config = readYaml(
      text: readFile('pipelines/sqre/ci_ci/test_pipeline_config_file.yaml')
    )
  }
}

echo config['a']
echo config['b']
echo config['c']
