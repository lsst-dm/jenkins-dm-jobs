import util.Plumber

def p = new Plumber(name: 'dax/release/tag-latest+dev', dsl: this)
p.pipeline()
