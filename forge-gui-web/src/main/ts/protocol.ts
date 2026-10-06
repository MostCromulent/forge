// Every message and field comes from protocol.gen.ts, and this file adds only the views the client reads objects through

import type { ClientMessage, Ref, TrackedProps, ZoneType } from './protocol.gen';

export type * from './protocol.gen';

/** Any object in the game's table. The table does not say which kind an object is. */
export type TrackedObject = TrackedProps & { $key: number };

// Each view is picked from TrackedProps, so a property Forge renames or retypes breaks the build here
type View<K extends keyof TrackedProps> = Pick<TrackedProps, K> & { $key: number };

export type CardView = View<'CurrentState' | 'Owner' | 'Controller' | 'Tapped' | 'Sickness' | 'Attacking' | 'Blocking'
  | 'PhasedOut' | 'Token' | 'Cloned' | 'Damage' | 'IsRingBearer' | 'Counters' | 'EntityAttachedTo' | 'Zone'
  | 'ShieldCount' | 'MustBlockCards' | 'BlockAdditional' | 'BlockAny'
  | 'ClassLevel' | 'CurrentRoom' | 'RingLevel' | 'Sprocket' | 'AttractionLights' | 'Intensity' | 'PlayerMayLook'
  | 'IsCommander' | 'UntilLeavesBattlefield' | 'Facedown' | 'OverlayText' | 'ChangedTypes' | 'ChangedColorWords'>;

export type CardStateView = View<'Name' | 'ImageKey' | 'Type' | 'ManaCost' | 'OriginalManaCost' | 'Power' | 'Toughness' | 'BasePower' | 'BaseToughness' | 'Loyalty'
  | 'Defense' | 'Keywords' | 'Colors' | 'RulesText' | 'OracleText' | 'AbilityText'>;

export type PlayerView = View<'Name' | 'Life' | 'IsAI' | 'HasPriority' | 'AvatarIndex' | 'AvatarCardImageKey'
  | 'SleeveIndex' | 'Counters' | 'Commander' | 'CommanderDamage' | 'CommanderCast' | 'Mana' | 'HasLost' | PlayerZone>;

export type GameView = View<'Players' | 'PlayerTurn' | 'Turn' | 'Phase' | 'Stack' | 'CombatView' | 'GameOver' | 'MatchOver'
  | 'WinningPlayerName' | 'NumGamesInMatch' | 'NumPlayedGamesInMatch' | 'PlanarPlayer' | 'StormCount'>;

export type StackItemView = View<'SourceCard' | 'ActivatingPlayer' | 'Ability' | 'Description' | 'SubInstance'
  | 'TargetCards' | 'TargetPlayers'>;

/** The zones a player holds cards in, each both a Forge zone and a player property listing its cards, which leaves out the stack. */
export type PlayerZone = Exclude<Extract<ZoneType, keyof TrackedProps>, 'Stack'>;

/** A list of references as the table holds them; an object Forge could not name is null. */
export type Refs = readonly (Ref | null)[];

export type Send = (msg: ClientMessage) => void;
