import { isAndroid } from "../android/bridge";
import enziMoviesIcon from "../../android/app/src/main/res/drawable-nodpi/enzi_movies.png";

export const appName = isAndroid ? "Enzi Movies" : "Streambert";
export const appIcon = isAndroid ? enziMoviesIcon : "./logo.svg";
