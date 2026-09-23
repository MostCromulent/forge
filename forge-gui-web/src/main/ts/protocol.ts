// Everything that crosses the socket, in both directions. The server writes these by hand in forge.web
// (WebSession, WebGuiGame, Lobby, DeckCatalog and friends), so a field renamed there must be renamed here.

/** A pointer to another object in the table. Objects never nest; they point at each other by key. */
export interface Ref {
  ref: number;
}

// ---- Game objects -------------------------------------------------------------------------------------------
// forge.trackable views, sent as bags of TrackableProperty values keyed by the property's name. A property still
// at its default is left out, so every field is optional.

export type Counters = Record<string, number>;

export interface CardView {
  $key: number;
  CurrentState?: Ref;
  Owner?: Ref;
  Controller?: Ref;
  Tapped?: boolean;
  Sickness?: boolean;
  Attacking?: boolean;
  Blocking?: boolean;
  PhasedOut?: boolean;
  Token?: boolean;
  Damage?: number;
  IsRingBearer?: boolean;
  Counters?: Counters;
  EntityAttachedTo?: Ref;
}

export interface CardStateView {
  $key: number;
  Name?: string;
  ImageKey?: string;
  Type?: string;
  ManaCost?: string;
  Power?: number;
  Toughness?: number;
  Loyalty?: string;
}

export interface CommanderDamage {
  card: Ref;
  value: number;
}

export type ZoneName = 'Hand' | 'Library' | 'Graveyard' | 'Exile' | 'Battlefield' | 'Command';

export interface PlayerView {
  $key: number;
  Name?: string;
  Life?: number;
  IsAI?: boolean;
  HasPriority?: boolean;
  AvatarIndex?: number;
  AvatarCardImageKey?: string;
  SleeveIndex?: number;
  Counters?: Counters;
  CommanderDamage?: CommanderDamage[];
  /** Mana in the pool, keyed by the colour's bit (1 W, 2 U, 4 B, 8 R, 16 G, 32 colourless). */
  Mana?: Record<number, number>;
  Hand?: Ref[];
  Library?: Ref[];
  Graveyard?: Ref[];
  Exile?: Ref[];
  Battlefield?: Ref[];
  Command?: Ref[];
}

/** One attack: who attacks what, and who blocks. */
export interface CombatBand {
  defender?: Ref;
  attackers?: Ref[];
  blockers?: Ref[];
  plannedBlockers?: Ref[];
}

export interface GameView {
  $key: number;
  Players?: Ref[];
  PlayerTurn?: Ref;
  Turn?: number;
  /** A forge.game.phase.PhaseType name, such as MAIN1. */
  Phase?: string;
  Stack?: Ref[];
  CombatView?: CombatBand[];
  MatchOver?: boolean;
  WinningPlayerName?: string;
}

export interface StackItemView {
  $key: number;
  SourceCard?: Ref;
  ActivatingPlayer?: Ref;
  Ability?: boolean;
  Description?: string;
  SubInstance?: Ref;
  TargetCards?: Ref[];
  TargetPlayers?: Ref[];
}

/** Any object in the table. The table does not say which kind an object is, so a reader picks the view it expects. */
export type TrackedObject = CardView & CardStateView & PlayerView & GameView & StackItemView;

// ---- Server to browser ----------------------------------------------------------------------------------------

export interface Hello {
  t: 'hello';
  host?: boolean;
  canClaimHost?: boolean;
  inMatch: boolean;
  inLobby?: boolean;
  spectating?: boolean;
  networked?: boolean;
  playerName?: string;
  avatars?: number[];
  sleeves?: number[];
  avatarCount: number;
  sleeveCount: number;
  sleeveArt?: SavedSleeveArt[];
  playmats?: Playmat[];
}

export interface SavedSleeveArt {
  key: string;
  offset: number;
}

export interface Playmat {
  id: string;
  label: string;
}

export interface DeckSummary {
  key: string;
  name: string;
  source: string;
  colors?: string;
  generated?: boolean;
  note?: string;
  formats?: string;
  legalIn?: string[];
  main: number;
  sideboard: number;
  problem?: string | null;
  sleeveArt?: string;
  sleeveOffset?: number;
}

export interface Decks {
  t: 'decks';
  decks: DeckSummary[];
  cardFormats?: string[];
}

export interface DeckCard {
  count: number;
  name: string;
  image: string;
}

export interface DeckDetails {
  key: string;
  name: string;
  colors?: string;
  problem?: string | null;
  stats: {
    main: number;
    sideboard: number;
    lands: number;
    curve: number[];
    types: { name: string; count: number }[];
    averageMana: number;
  };
  main: { heading: string; cards: DeckCard[] }[];
  sideboard: DeckCard[];
}

export interface Format {
  id: string;
  name: string;
}

/** A seat's type is a netplay lobby slot's: LOCAL, AI, OPEN or REMOTE. */
export interface Seat {
  type: string;
  name: string;
  mine: boolean;
  mayEdit: boolean;
  ready?: boolean;
  avatar: number;
  sleeve: number;
  sleeveArt?: string;
  sleeveOffset?: number;
  deck?: string | null;
  deckName?: string | null;
  deckSize?: number;
  colors?: string;
  problem?: string | null;
}

export interface LobbyState {
  t: 'lobby';
  open: boolean;
  host: boolean;
  shareable?: boolean;
  format: string;
  formats: Format[];
  seats: Seat[];
  maxSeats: number;
  mySeat: number;
  problems?: string[];
  canStart: boolean;
}

export interface Address {
  label: string;
  url: string;
}

export interface StateMessage {
  t: 'state';
  seq: number;
  full: boolean;
  root: number;
  newObjects: Record<string, Omit<TrackedObject, '$key'>>;
  deltas: Record<string, Record<string, unknown>>;
  visible: number[];
  localPlayers: number[];
}

export interface PromptButton {
  label: string;
  enabled: boolean;
}

export interface Prompt {
  t: 'prompt';
  message?: string;
  ok?: PromptButton;
  cancel?: PromptButton;
  focusOk?: boolean;
  priority?: boolean;
  paying?: boolean;
  card?: Ref | null;
  selectable?: Ref[];
  selectableMin?: number;
  selectablePlayers?: Ref[];
  highlighted?: number[];
}

export interface ShownZone {
  player: Ref;
  zone: ZoneName;
}

// ---- Requests: questions the game waits on, answered with {t:'reply'} ------------------------------------------

export interface RequestOption {
  label?: string;
  name?: string;
  imageKey?: string | null;
  card?: Ref;
  total?: number;
}

interface RequestBase {
  t: 'request';
  id: number;
  title?: string;
  message?: string;
  default: unknown;
  card?: Ref | null;
}

export interface ChoicesRequest extends RequestBase {
  kind: 'choices' | 'reveal';
  min: number;
  max: number;
  options: RequestOption[];
  selected: number[];
  /** Set when the choice is between spells on the stack, which are picked there instead. */
  stackKeys?: number[];
  atX?: number;
  atY?: number;
}

export interface OrderRequest extends RequestBase {
  kind: 'order';
  top: string;
  min: number;
  max: number;
  options: RequestOption[];
  selected: number[];
  remember: boolean;
}

export interface ManipulateRequest extends RequestBase {
  kind: 'manipulate';
  options: RequestOption[];
  movable: number[];
  toTop: boolean;
  toBottom: boolean;
  toAnywhere: boolean;
}

export interface OptionRequest extends RequestBase {
  kind: 'option';
  labels: string[];
  default: number;
}

export interface TextRequest extends RequestBase {
  kind: 'text';
  initial?: string | null;
  numeric: boolean;
}

export interface DistributeRequest extends RequestBase {
  kind: 'distribute';
  amount: number;
  perMin: number;
  options: RequestOption[];
  maySkip: boolean;
  default: number[];
}

export interface SideboardEntry extends RequestOption {
  name: string;
  total: number;
}

export interface SideboardRequest extends RequestBase {
  kind: 'sideboard';
  entries: SideboardEntry[];
  main: number[];
}

export type Request = ChoicesRequest | OrderRequest | ManipulateRequest | OptionRequest | TextRequest
  | DistributeRequest | SideboardRequest;

export interface HostChoice {
  t: 'hostChoice';
  id: number;
  kind?: string;
  message?: string;
  options: string[];
  min?: number;
  max?: number;
}

export interface TurnMarker {
  phase: string;
  mine: boolean;
}

/** Forge preferences the options dialog shares with the desktop client. */
export type ServerSettings = Record<string, string | number | boolean | undefined> & { highlightColor?: string };

export interface Controls {
  t: 'controls';
  autoPass: boolean;
  dayTime?: string | null;
  myStops: string[];
  otherStops: string[];
  marker?: TurnMarker;
  settings: ServerSettings;
}

export interface Playable {
  t: 'playable';
  cards: Ref[];
  autoTap: Ref[];
}

export interface LogEntry {
  type: string;
  message: string;
  card?: number;
  imageKey?: string;
}

export interface LogMessage {
  t: 'log';
  full: boolean;
  entries: LogEntry[];
}

export interface CardFace {
  name?: string;
  cost?: string;
  type?: string;
  text?: string;
  pt?: string;
  imageKey?: string;
}

export interface Detail {
  t: 'detail';
  key: number;
  faces: CardFace[];
}

export interface PlayerDetail {
  t: 'playerDetail';
  key: number;
  name?: string;
  lines: string[];
}

export interface StackMenu {
  t: 'stackMenu';
  key: number;
  autoYield?: boolean;
  trigger?: 'ACCEPT' | 'DECLINE' | 'ASK';
}

export interface Notice {
  t: 'notice';
  title?: string;
  message?: string;
  error?: boolean;
}

export interface Sound {
  t: 'sound';
  name: string;
  sync?: boolean;
}

export interface Printing {
  key: string;
  name: string;
  edition: string;
}

export type ServerMessage =
  | Hello
  | Decks
  | LobbyState
  | { t: 'addresses'; list: Address[] }
  | { t: 'chat'; from: string; text: string }
  | { t: 'deckDetails'; deck: DeckDetails }
  | { t: 'cardSearch'; names: string[] }
  | { t: 'printings'; name: string; printings: Printing[] }
  | HostChoice
  | { t: 'error'; message: string }
  | StateMessage
  | Prompt
  | { t: 'zones'; show: ShownZone[] }
  | Request
  | { t: 'gameOver' }
  | Sound
  | Playable
  | Controls
  | LogMessage
  | Detail
  | PlayerDetail
  | StackMenu
  | Notice
  | { t: 'flash' };

// ---- Browser to server ---------------------------------------------------------------------------------------

export type SeatChange = { avatar: number } | { name: string } | { deck: string } | { sleeve: number };

export type ClientMessage =
  // Match
  | { t: 'selectCard'; key: number; menu: boolean; x: number; y: number }
  | { t: 'selectPlayer'; key: number }
  | { t: 'useMana'; color: number }
  | { t: 'ok' | 'cancel' | 'endTurn' | 'autoPass' | 'undo' | 'concede' | 'leave' }
  | { t: 'nextGame'; decision: 'CONTINUE' | 'QUIT' }
  | { t: 'reply'; id: number; value: unknown }
  | { t: 'detail' | 'playerDetail' | 'stackMenu'; key: number }
  | { t: 'stackYield'; key: number; action: string }
  | { t: 'toggleStop' | 'toggleMarker'; phase: string; mine: boolean }
  | { t: 'setSetting'; key: string; value: string }
  // Start page and lobby
  | { t: 'decks' | 'lobby' | 'invite' | 'leaveLobby' | 'addSeat' | 'addresses' | 'claimHost' | 'quit' | 'netDecks' }
  | { t: 'chat'; text: string }
  | { t: 'start'; spectate: boolean }
  | { t: 'setFormat'; format: string }
  | ({ t: 'setSeat'; index: number } & SeatChange)
  | { t: 'openSeat' | 'aiSeat' | 'removeSeat'; index: number }
  | { t: 'hostChoice'; id: number; value: number[] }
  | { t: 'deckDetails'; key: string }
  | { t: 'cardSearch'; query: string }
  | { t: 'printings'; name: string }
  | { t: 'sleeveArt'; index: number; key: string; offset: number };

export type Send = (msg: ClientMessage) => void;
