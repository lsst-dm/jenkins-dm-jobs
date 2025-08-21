freeStyleJob('seed-job') {
    label('jenkins-manager')
    configure { project ->
        project / canRoam(false)
    }
    scm {
        git {
            remote {
                name('origin')
                url('https://github.com/lsst-dm/jenkins-dm-jobs')
            }
            branches('tickets/DM-52263')
        }
    }
    steps {
        shell('./gradlew libs')
        dsl {
            external('jobs/*.groovy')
        }
    }
}
