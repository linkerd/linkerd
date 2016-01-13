package io.buoyant.linkerd;

import com.fasterxml.jackson.core.JsonParser;
import com.twitter.finagle.*;
import com.twitter.util.Activity;
import com.twitter.util.Var;
import com.twitter.util.Var$;
import scala.Option;
import scala.collection.Seq;
import scala.collection.immutable.Set;
import scala.runtime.AbstractFunction0;
import scala.runtime.AbstractFunction1;

public class JTestNamer extends NamerInitializer {

  private static class Buh {
    private boolean buh;
    public Buh(boolean buh) {
      this.buh = buh;
    }

    public static Stack.Param<Buh> param = Stack.Param$.MODULE$.apply(new AbstractFunction0<Buh>() {
      @Override
      public Buh apply() {
        return new Buh(false);
      }
    });

    public static Parsing.Param parser() {
      return Parsing.Param$.MODULE$.Boolean(
          "buh",
          new AbstractFunction1() {
            @Override
            public Object apply(Object b) {
              return new Buh((Boolean) b);
            }
          },
          param
      );
    }
  }

  private Stack.Params params;
  private static final Stack.Params defaultParams = Stack.Params$.MODULE$.empty().$plus(
      Prefix$.MODULE$.apply(Path$.MODULE$.read("/foo")), Prefix$.MODULE$
  );

  public JTestNamer(Stack.Params params) {
    this.params = params;
  }

  public JTestNamer() {
    this.params = defaultParams;
  }

  @Override
  public Stack.Params params() {
    return params;
  }

  @Override
  public NamerInitializer withParams(Stack.Params ps) {
    return new JTestNamer(ps);
  }

  @Override
  public Set<String> paramKeys() {
    return Buh.parser().keys();
  }

  @Override
  public NamerInitializer readParam(String key, JsonParser p) {
    return withParams(Buh.parser().read(key, p, params));
  }

  private class NamerImpl implements Namer {

    private boolean startsWithBuh(Option<Seq<String>> elemsOpt) {
      return elemsOpt.exists(new AbstractFunction1<Seq<String>, Object>() {
        @Override
        public Object apply(Seq<String> elems) {
          return elems.headOption().exists(new AbstractFunction1<String, Object>() {
            @Override
            public Object apply(String head) {
              return head.equals("buh");
            }
          });
        }
      });
    }

    @Override
    public Activity<NameTree<Name>> lookup(Path path) {
      boolean buh = ((Buh) params.apply(Buh.param)).buh;
      NameTree t;
      if (startsWithBuh(Path.Utf8$.MODULE$.unapplySeq(path)) && !buh) {
        t = NameTree.Neg$.MODULE$;
      } else {
        Var addr = Var$.MODULE$.value(Addr.Pending$.MODULE$);
        t = new NameTree.Leaf(Name.Bound$.MODULE$.apply(addr, prefix(), path));
      }
      return Activity.value(t);
    }

    @Override
    public Activity<NameTree<Name.Bound>> bind(NameTree<Path> tree) {
      return Namer$class.bind(this, tree);
    }
  }

  @Override
  public Namer newNamer() {
    return new NamerImpl();
  }
}
