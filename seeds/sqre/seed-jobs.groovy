freeStyleJob('seed-job') {
    // prod and dev load this same file; each has only one of these nodes
    label('manager-0 || manager-dev-0')
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
