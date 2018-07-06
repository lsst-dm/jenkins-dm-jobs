import util.Plumber
import org.yaml.snakeyaml.Yaml

def sqre = new Yaml().load(readFileFromWorkspace('etc/sqre/config.yaml'))

def p = new Plumber(name: 'sqre/infra/build-s3sync', dsl: this)
p.pipeline().with {
  description('Constructs s3sync docker images.')

  parameters {
    stringParam('AWSCLI_VER', sqre.awscli.pypi.version, 'awscli version to install')

    booleanParam('PUBLISH', true, 'Push image to docker registry.')
    booleanParam('LATEST', false, 'Also push to docker registry with `latest` tag.')
  }
}
