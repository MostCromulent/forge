package forge.web;

import org.testng.annotations.Test;

import java.util.List;

import static org.testng.Assert.assertEquals;

/** Which seat's pack moved, read from two readings of every seat's pack count around one seat's pick. */
public class DraftViewTest {
    // Fails if a pick that passed a pack is not drawn as a move, in either direction
    @Test
    public void aPassIsAMove() {
        assertEquals(DraftView.moved(new int[] {1, 1, 1, 1}, new int[] {0, 2, 1, 1}, 0, 1), List.of(0));
        assertEquals(DraftView.moved(new int[] {1, 1, 1, 1}, new int[] {0, 1, 1, 2}, 0, -1), List.of(0));
        assertEquals(DraftView.moved(new int[] {1, 2, 1, 1}, new int[] {1, 1, 1, 2}, 1, -1), List.of());
        assertEquals(DraftView.moved(new int[] {1, 2, 1, 1}, new int[] {2, 1, 1, 1}, 1, -1), List.of(1));
    }

    // Fails if a pack that ran out, a kept pack or a new round is drawn as a pack sliding to the next seat
    @Test
    public void nothingElseIsAMove() {
        assertEquals(DraftView.moved(new int[] {1, 0, 1, 1}, new int[] {0, 0, 1, 1}, 0, 1), List.of(), "the pack ran out");
        assertEquals(DraftView.moved(new int[] {1, 1, 1, 1}, new int[] {1, 1, 1, 1}, 0, 1), List.of(), "a double pick kept the pack");
        assertEquals(DraftView.moved(new int[] {0, 0, 0, 0}, new int[] {1, 1, 1, 1}, 2, 1), List.of(), "a new round");
        assertEquals(DraftView.moved(new int[] {1, 1, 1}, new int[] {0, 2, 1, 1}, 0, 1), List.of(), "readings of different pods");
        assertEquals(DraftView.moved(null, new int[] {1, 1}, 0, 1), List.of(), "no earlier reading");
    }
}
