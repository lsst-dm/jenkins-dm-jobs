import util.Plumber

def p = new Plumber(name: 'dax/release/rebuild_publish_qserv-dev', dsl: this)
p.pipeline()
