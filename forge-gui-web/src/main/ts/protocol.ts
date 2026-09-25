// The protocol as the browser uses it. Every message and field comes from protocol.gen.ts, which is generated from
// the Java records in forge.web.ToBrowser and FromBrowser; this file adds only the views the client reads objects
// through.

import type { ClientMessage, Ref, TrackedProps, ZoneType } from './protocol.gen';

export type * from './protocol.gen';

/** Any object in the game's table. The table does not say which kind an object is. */
export type TrackedObject = TrackedProps & { $key: number };

// Each view names the properties the client reads from that kind of object. They are picked from TrackedProps, so
// a property Forge renames or retypes breaks the build here rather than going quiet in the browser.
type View<K extends keyof TrackedProps> = Pick<TrackedProps, K> & { $key: number };

export type CardView = View<'CurrentState' | 'Owner' | 'Controller' | 'Tapped' | 'Sickness' | 'Attacking' | 'Blocking'
  | 'PhasedOut' | 'Token' | 'Cloned' | 'Damage' | 'IsRingBearer' | 'Counters' | 'EntityAttachedTo' | 'Zone'
  | 'ShieldCount' | 'MustBlockCards' | 'BlockAdditional' | 'BlockAny'
  | 'ClassLevel' | 'CurrentRoom' | 'RingLevel' | 'Sprocket' | 'AttractionLights' | 'Intensity' | 'PlayerMayLook'
  | 'IsCommander'>;

export type CardStateView = View<'Name' | 'ImageKey' | 'Type' | 'ManaCost' | 'Power' | 'Toughness' | 'Loyalty'
  | 'Defense' | 'Keywords' | 'Colors' | 'RulesText'>;

export type PlayerView = View<'Name' | 'Life' | 'IsAI' | 'HasPriority' | 'AvatarIndex' | 'AvatarCardImageKey'
  | 'SleeveIndex' | 'Counters' | 'CommanderDamage' | 'CommanderCast' | 'Mana' | 'HasLost' | PlayerZone>;

export type GameView = View<'Players' | 'PlayerTurn' | 'Turn' | 'Phase' | 'Stack' | 'CombatView' | 'GameOver' | 'MatchOver'
  | 'WinningPlayerName' | 'PlanarPlayer'>;

export type StackItemView = View<'SourceCard' | 'ActivatingPlayer' | 'Ability' | 'Description' | 'SubInstance'
  | 'TargetCards' | 'TargetPlayers'>;

/** The zones a player holds cards in: each is both a Forge zone and a player property listing them. The stack
 *  is the game's, not a player's. */
export type PlayerZone = Exclude<Extract<ZoneType, keyof TrackedProps>, 'Stack'>;

/** The zones the board draws on its own; any other opens in a panel when the game shows it. */
export type ZoneName = Extract<PlayerZone, 'Hand' | 'Library' | 'Graveyard' | 'Exile' | 'Battlefield' | 'Command'>;

/** A list of references as the table holds them; an object Forge could not name is null. */
export type Refs = readonly (Ref | null)[];

export type Send = (msg: ClientMessage) => void;
