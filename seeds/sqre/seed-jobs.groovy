freeStyleJob('seed-job') {
    label('jenkins-manager')
    scm {
        git {
            remote {
                name('origin')
                url('https://github.com/lsst-dm/jenkins-dm-jobs')
            }
            branches('main')
        }
    }
    steps {
        shell('./gradlew libs')
        dsl {
            external('jobs/*.groovy')
        }
    }
}
