#@namespace scala io.buoyant.namerd.iface.thriftscala
namespace java io.buoyant.namerd.iface.thriftjava

// Equivalent to a finagle Path
typedef list<binary> Path
typedef string Ns
struct Void {}

// An opaque server-provided stamp, usd to implement watches.
typedef binary Stamp

// References a specific version of a name.
struct NameRef {
  1: Stamp stamp
  2: Path name
  3: Ns ns
}

/*
 * NAME RESOLUTION
 *
 * Produces a NameTree[Name.Bound]
 */

typedef string Dtab

struct BindReq {
  1: Dtab dtab
  2: NameRef name
  3: Path clientId
}

struct BoundName {
  1: Path id
  2: Path residual
}

typedef i32 BoundNodeId

struct WeightedNodeId {
  1: double weight
  2: BoundNodeId id
}

union BoundNode {
  1: Void neg
  2: Void empty
  3: Void fail
  4: BoundName leaf
  5: list<BoundNodeId> alt
  6: list<WeightedNodeId> weighted
}

struct BoundTree {
  1: BoundNode root
  2: map<BoundNodeId, BoundNode> nodes
}

struct Bound {
  1: Stamp stamp
  2: BoundTree tree
  3: Ns ns
}

exception BindFailure {
  1: string reason
  2: i32 retryInSeconds // DEPRECATED & IGNORED
  3: NameRef name
  4: Ns ns
}

/*
 * ADDRESS RESOLUTION
 *
 * Once a name is bound, the bound id may be used to watch address updates. 
 */

struct AddrReq {
  1: NameRef name
  2: Path clientId
}

struct AddrMeta {
  // HTTP/1.1 Host or HTTP/2.0 :authority to use on outbound requests.
  1: optional string authority

  // In scheduled environments, the name of the node that this
  // address is scheduled on.
  2: optional string nodeName

  // Endpoints may be weighted. Ignored on BoundAddr.
  3: optional double endpoint_addr_weight
}

struct TransportAddress {
  1: binary ip // An IP address of some sort (IPv4 or IPv6)
  2: i32 port // A port number on [1, 65535]
  3: optional AddrMeta meta
}

struct BoundAddr {
  1: set<TransportAddress> addresses
  2: optional AddrMeta meta
}

union AddrVal {
  1: BoundAddr bound
  2: Void neg
}

struct Addr {
  1: Stamp stamp
  2: AddrVal value
}

exception AddrFailure {
  1: string reason
  2: i32 retryInSeconds // DEPRECATED & IGNORED
  3: NameRef name
}

/*
 * DELEGATION
 */

typedef i32 DelegateNodeId

struct Transformation {
  1: BoundName value
  2: DelegateNodeId tree
}

union DelegateContents {
  1: string error
  2: Void empty
  3: Void fail
  4: Void neg
  5: DelegateNodeId delegate
  6: BoundName boundLeaf
  7: Path pathLeaf
  8: list<DelegateNodeId> alt
  9: list<WeightedNodeId> weighted
  10: Transformation transformation
}

struct DelegateNode {
  1: Path path
  2: string dentry
  3: DelegateContents contents
}

struct DelegateTree {
  1: DelegateNode root
  2: map<DelegateNodeId, DelegateNode> nodes
}

struct Delegation {
  // Delegation does not support long-polling. Clients should always send an empty stamp.
  1: Stamp stamp // DEPRECATED
  2: DelegateTree tree
  3: Ns ns
}

struct DelegateReq {
  1: Dtab dtab
  2: Delegation delegation
  3: Path clientId
}

exception DelegationFailure {
  1: string reason
}

/*
 * DTAB
 */

struct DtabReq {
  1: Stamp stamp
  2: Ns ns
  3: Path clientId
}

struct DtabRef {
  1: Stamp stamp
  2: Dtab dtab
}

exception DtabFailure {
  1: string reason
}

/*
 * Namer as a service.
 *
 * Combines refinement and binding.
 */
service Namer {
  Bound bind(1: BindReq req) throws(1: BindFailure rf)
  Addr addr(1: AddrReq req) throws(1: AddrFailure af)
  Delegation delegate(1: DelegateReq req) throws (1: DelegationFailure df)
  DtabRef dtab(1: DtabReq req) throws (1: DtabFailure df)
}
